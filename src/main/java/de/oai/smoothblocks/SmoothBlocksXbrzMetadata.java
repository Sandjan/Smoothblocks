package de.oai.smoothblocks;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.BitSet;

/** Owns the compact RG8UI xBRZ edge metadata atlas. */
public final class SmoothBlocksXbrzMetadata {
    private SmoothBlocksXbrzMetadata() {}

    private static final int[] PACK_PARAMETERS = {
            GL11C.GL_PACK_ALIGNMENT,
            GL11C.GL_PACK_ROW_LENGTH,
            GL12C.GL_PACK_IMAGE_HEIGHT,
            GL11C.GL_PACK_SKIP_ROWS,
            GL11C.GL_PACK_SKIP_PIXELS,
            GL12C.GL_PACK_SKIP_IMAGES,
            GL11C.GL_PACK_SWAP_BYTES,
            GL11C.GL_PACK_LSB_FIRST
    };

    private static final int[] UNPACK_PARAMETERS = {
            GL11C.GL_UNPACK_ALIGNMENT,
            GL11C.GL_UNPACK_ROW_LENGTH,
            GL12C.GL_UNPACK_IMAGE_HEIGHT,
            GL11C.GL_UNPACK_SKIP_ROWS,
            GL11C.GL_UNPACK_SKIP_PIXELS,
            GL12C.GL_UNPACK_SKIP_IMAGES,
            GL11C.GL_UNPACK_SWAP_BYTES,
            GL11C.GL_UNPACK_LSB_FIRST
    };
    private static final int[] POINTER_UNPACK = new int[UNPACK_PARAMETERS.length];

    private static volatile int textureId;
    private static volatile int atlasWidth;
    private static volatile int atlasHeight;
    private static volatile long classifiedSprites;
    private static volatile long classifiedTexels;
    private static volatile long classifiedEdgeTexels;
    private static volatile long animatedFallbackSprites;
    private static volatile long buildNanos;
    private static volatile String sourceMode = "none";
    private static volatile long sourceComparedTexels;
    private static volatile long sourcePixelMismatches;
    private static volatile long uvMismatchSprites;
    private static volatile long overlappingTexels;
    private static volatile long uploadMismatchedBytes;
    private static volatile int sourceTextureId;

    /**
     * Build pass-0 from the exact level-0 GPU atlas that pass-1 samples.
     *
     * <p>This is deliberately stronger than reading SpriteContents. Resource processing,
     * upload-time conversion, padding and implementation details are all removed from the
     * equivalence question: the classifier now sees byte-for-byte the same RGBA source as
     * the fragment shader. Each sprite is still classified as an isolated image, so the
     * hard-boundary bits exactly match the standalone lab.</p>
     */
    public static synchronized void rebuild(TextureAtlas atlas, SpriteLoader.Preparations preparations) {
        long start = System.nanoTime();
        int expectedWidth = preparations.width();
        int expectedHeight = preparations.height();
        if (expectedWidth <= 0 || expectedHeight <= 0) {
            close();
            return;
        }

        final AtlasSnapshot snapshot;
        try {
            snapshot = captureLevel0(atlas.getTexture());
        } catch (Throwable t) {
            System.err.println("[SmoothBlocks] xBRZ GPU atlas readback failed; metadata disabled: " + t);
            close();
            sourceMode = "gpu-failed";
            buildNanos = System.nanoTime() - start;
            return;
        }

        if (snapshot.width != expectedWidth || snapshot.height != expectedHeight) {
            System.err.println("[SmoothBlocks] xBRZ atlas dimension mismatch: preparations="
                    + expectedWidth + "x" + expectedHeight + " gpu=" + snapshot.width + "x" + snapshot.height);
            close();
            sourceMode = "gpu-size-mismatch";
            buildNanos = System.nanoTime() - start;
            return;
        }

        int width = snapshot.width;
        int height = snapshot.height;
        long byteCountLong = (long) width * height * 2L;
        if (byteCountLong > Integer.MAX_VALUE) {
            System.err.println("[SmoothBlocks] xBRZ metadata atlas too large: " + width + "x" + height);
            close();
            return;
        }

        short[] extra = SmoothBlocksAnimatedMetadata.prepare(preparations, snapshot.argb);
        int metadataHeight = Math.addExact(height, (extra.length + width - 1) / width);
        if (metadataHeight > GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE))
            throw new IllegalStateException("Animated block metadata exceeds GPU texture size");
        ByteBuffer data = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width, metadataHeight), 2));
        try {
            // 0xFFFE = not sprite content / unavailable. Static sprite content is NEVER
            // intentionally LINEAR. 0xFFFF retains the linear path for animated effects;
            // animated blocks instead point to immutable, prepared frame metadata.
            for (int i = 0; i < data.capacity(); i += 2) {
                data.put(i,     (byte) 0xFE);
                data.put(i + 1, (byte) 0xFF);
            }
            for (int i = 0; i < extra.length; i++) putMeta(data, (width * height + i) * 2, extra[i] & 0xFFFF);

            long sprites = 0;
            long texels = 0;
            long edgeTexels = 0;
            long animated = 0;
            long compared = 0;
            long mismatches = 0;
            long uvBad = 0;
            long overlaps = 0;
            BitSet occupied = new BitSet(width * height);

            for (Map.Entry<?, TextureAtlasSprite> entry : preparations.regions().entrySet()) {
                TextureAtlasSprite sprite = entry.getValue();
                if (sprite == null || sprite.contents() == null) continue;

                int sw = sprite.contents().width();
                int sh = sprite.contents().height();
                if (sw <= 0 || sh <= 0) continue;

                // getU0/getV0 are the UVs of actual sprite content, not its padded stitch
                // allocation. Convert those UVs back to the exact level-0 atlas texel.
                int ox = Math.round(sprite.getU0() * width);
                int oy = Math.round(sprite.getV0() * height);

                // If these spans do not round back to the logical sprite dimensions,
                // atlas UVs and metadata coordinates cannot be one-to-one. Keep this as
                // a compact F7 invariant rather than printing one line per sprite.
                int uvw = Math.round((sprite.getU1() - sprite.getU0()) * width);
                int uvh = Math.round((sprite.getV1() - sprite.getV0()) * height);
                if (uvw != sw || uvh != sh) uvBad++;

                if (ox < 0 || oy < 0 || ox + sw > width || oy + sh > height) {
                    System.err.println("[SmoothBlocks] xBRZ sprite outside GPU atlas bounds: " + sprite);
                    continue;
                }

                // A stitched content texel must belong to exactly one sprite. If this
                // invariant is violated, later writes can silently replace STATIC metadata
                // with ANIMATED/MISSING-looking data from another entry.
                for (int y = 0; y < sh; y++) {
                    int row = (oy + y) * width + ox;
                    for (int x = 0; x < sw; x++) {
                        int index = row + x;
                        if (occupied.get(index)) overlaps++;
                        else occupied.set(index);
                    }
                }

                if (sprite.contents().isAnimated()) {
                    int marker = SmoothBlocksAnimatedMetadata.marker(sprite);
                    fillRect(data, width, ox, oy, sw, sh, marker);
                    if (marker == SmoothBlocksXbrzClassifier.META_ANIMATED_LINEAR) animated++;
                    continue;
                }

                int[] spriteArgb = cropArgb(snapshot.argb, width, ox, oy, sw, sh);
                SmoothBlocksXbrzCore.Result classified = SmoothBlocksAnimatedMetadata.takeStaticResult(sprite);
                boolean periodic = classified != null;
                if (classified == null) classified = SmoothBlocksXbrzCore.classifyArgb(sw, sh, spriteArgb);
                short[] meta = classified.metadata();

                // Diagnostic only: compare the former SpriteContents source against the
                // actual GPU source. A non-zero value proves why pass-0/pass-1 diverged.
                // Failure to introspect SpriteContents does not affect the new GPU path.
                try {
                    int[] oldArgb = SmoothBlocksXbrzClassifier.readArgb(sprite);
                    if (oldArgb.length == spriteArgb.length) {
                        for (int i = 0; i < oldArgb.length; i++) {
                            compared++;
                            if (oldArgb[i] != spriteArgb[i]) mismatches++;
                        }
                    }
                } catch (Throwable ignored) {
                    // GPU snapshot is authoritative; old private NativeImage access is optional.
                }

                for (int y = 0; y < sh; y++) {
                    int srcRow = y * sw;
                    int dstRow = (oy + y) * width + ox;
                    for (int x = 0; x < sw; x++) {
                        int value = periodic && (x == 0 || y == 0 || x == sw - 1 || y == sh - 1)
                                ? SmoothBlocksAnimatedMetadata.marker(sprite) : meta[srcRow + x] & 0xFFFF;
                        putMeta(data, (dstRow + x) * 2, value);
                    }
                }
                sprites++;
                texels += (long) sw * sh;
                edgeTexels += classified.stats().texelsWithEdge();
            }

            long uploadDiff = replaceGlTexture(width, metadataHeight, data);
            atlasWidth = width;
            atlasHeight = height;
            classifiedSprites = sprites;
            classifiedTexels = texels;
            classifiedEdgeTexels = edgeTexels;
            animatedFallbackSprites = animated;
            sourceMode = "gpu";
            sourceTextureId = snapshot.textureId;
            sourceComparedTexels = compared;
            sourcePixelMismatches = mismatches;
            uvMismatchSprites = uvBad;
            overlappingTexels = overlaps;
            uploadMismatchedBytes = uploadDiff;
            buildNanos = System.nanoTime() - start;

            SmoothBlocksClient.noteXbrzMetadataBuilt(width, height, sprites, texels, animated, buildNanos);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    static AtlasSnapshot captureLevel0(GpuTexture texture) {
        RenderSystem.assertOnRenderThread();
        if (!(texture instanceof GlTexture glTexture) || texture.isClosed()) {
            throw new IllegalStateException("block atlas is not a live OpenGL texture");
        }
        if (texture.getFormat() != TextureFormat.RGBA8 || texture.getDepthOrLayers() != 1 || texture.getMipLevels() < 1) {
            throw new IllegalStateException("block atlas is not 2D RGBA8");
        }
        if (GL.getCapabilities().glGetTextureImage == 0L) {
            throw new IllegalStateException("glGetTextureImage unavailable");
        }

        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        long countLong = (long) width * height;
        if (width <= 0 || height <= 0 || countLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("invalid block-atlas dimensions " + width + "x" + height);
        }

        int[] rgbaNative = new int[(int) countLong];
        int previousBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] previousPack = new int[PACK_PARAMETERS.length];
        for (int i = 0; i < PACK_PARAMETERS.length; i++) {
            previousPack[i] = GL11C.glGetInteger(PACK_PARAMETERS[i]);
        }

        try {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            for (int i = 0; i < PACK_PARAMETERS.length; i++) {
                GL11C.glPixelStorei(PACK_PARAMETERS[i], i == 0 ? Integer.BYTES : 0);
            }
            GL45C.glGetTextureImage(glTexture.glId(), 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, rgbaNative);
        } finally {
            try {
                for (int i = 0; i < PACK_PARAMETERS.length; i++) {
                    GL11C.glPixelStorei(PACK_PARAMETERS[i], previousPack[i]);
                }
            } finally {
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousBuffer);
            }
        }

        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            for (int i = 0; i < rgbaNative.length; i++) rgbaNative[i] = Integer.reverseBytes(rgbaNative[i]);
        }

        int[] argb = rgbaNative;
        for (int i = 0; i < rgbaNative.length; i++) {
            // glGetTextureImage(GL_RGBA, GL_UNSIGNED_BYTE) -> native little-endian
            // int 0xAABBGGRR. Convert explicitly to Java ARGB 0xAARRGGBB.
            int p = rgbaNative[i];
            int r = p & 0xFF;
            int g = (p >>> 8) & 0xFF;
            int b = (p >>> 16) & 0xFF;
            int a = (p >>> 24) & 0xFF;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return new AtlasSnapshot(width, height, argb, glTexture.glId());
    }

    private static int[] cropArgb(int[] atlas, int atlasWidth, int x0, int y0, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(atlas, (y0 + y) * atlasWidth + x0, out, y * w, w);
        }
        return out;
    }

    private static void putMeta(ByteBuffer data, int dst, int value) {
        data.put(dst,     (byte) (value & 0xFF));
        data.put(dst + 1, (byte) ((value >>> 8) & 0xFF));
    }

    private static void fillRect(ByteBuffer data, int atlasWidth, int x0, int y0, int w, int h, int value) {
        for (int y = 0; y < h; y++) {
            int row = (y0 + y) * atlasWidth + x0;
            for (int x = 0; x < w; x++) putMeta(data, (row + x) * 2, value);
        }
    }

    private static long replaceGlTexture(int width, int height, ByteBuffer data) {
        int old = textureId;
        if (old != 0) {
            GlStateManager._deleteTexture(old);
            textureId = 0;
        }

        int tex = uploadMetadata(width, height, data);
        long mismatches = verifyUpload(tex, data);
        textureId = tex;
        return mismatches;
    }

    /** Shared upload with explicit pixel-store preservation; caller owns the GL texture. */
    static int uploadMetadata(int width, int height, ByteBuffer data) {
        int tex = GlStateManager._genTexture();
        final int scratchUnit = 11;
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int previousUnpackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] previousUnpack = new int[UNPACK_PARAMETERS.length];
        for (int i = 0; i < UNPACK_PARAMETERS.length; i++) {
            previousUnpack[i] = GL11C.glGetInteger(UNPACK_PARAMETERS[i]);
        }

        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + scratchUnit);
        int previousBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            // Never inherit pixel-store state from a resource-pack/driver upload. RG8UI
            // is two bytes per texel; a stale row length/skip is enough to make the edge
            // map look like unrelated repeated sprites even though CPU metadata is valid.
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < UNPACK_PARAMETERS.length; i++) {
                GL11C.glPixelStorei(UNPACK_PARAMETERS[i], i == 0 ? 1 : 0);
            }

            GlStateManager._bindTexture(tex);
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            data.position(0);
            GlStateManager._texImage2D(
                    GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RG8UI, width, height, 0,
                    GL30C.GL_RG_INTEGER, GL11C.GL_UNSIGNED_BYTE, data
            );
        } finally {
            try {
                for (int i = 0; i < UNPACK_PARAMETERS.length; i++) {
                    GL11C.glPixelStorei(UNPACK_PARAMETERS[i], previousUnpack[i]);
                }
                GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, previousUnpackBuffer);
            } finally {
                GlStateManager._bindTexture(previousBinding);
                GlStateManager._activeTexture(previousActive);
            }
        }

        return tex;
    }

    /** Update only the two address cells, preserving unpack/PBO state and all texture bindings. */
    static void updatePointer(int cell, int width, int baseHeight, ByteBuffer address) {
        updatePointer(textureId, cell, width, baseHeight, address);
    }

    static void updatePointer(int texture, int cell, int width, int baseHeight, ByteBuffer address) {
        int previousBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] unpack = POINTER_UNPACK;
        for (int i = 0; i < unpack.length; i++) unpack[i] = GL11C.glGetInteger(UNPACK_PARAMETERS[i]);
        try {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < unpack.length; i++) GL11C.glPixelStorei(UNPACK_PARAMETERS[i], i == 0 ? 1 : 0);
            address.position(0).limit(4);
            int x = cell % width, y = baseHeight + cell / width;
            if (x + 1 < width) {
                GL45C.glTextureSubImage2D(texture, 0, x, y, 2, 1, GL30C.GL_RG_INTEGER, GL11C.GL_UNSIGNED_BYTE, address);
            } else {
                address.limit(2);
                GL45C.glTextureSubImage2D(texture, 0, x, y, 1, 1, GL30C.GL_RG_INTEGER, GL11C.GL_UNSIGNED_BYTE, address);
                address.position(2).limit(4);
                GL45C.glTextureSubImage2D(texture, 0, 0, y + 1, 1, 1, GL30C.GL_RG_INTEGER, GL11C.GL_UNSIGNED_BYTE, address);
            }
        } finally {
            address.position(0).limit(4);
            for (int i = 0; i < unpack.length; i++) GL11C.glPixelStorei(UNPACK_PARAMETERS[i], unpack[i]);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, previousBuffer);
        }
    }

    private static long verifyUpload(int tex, ByteBuffer expected) {
        ByteBuffer actual = MemoryUtil.memAlloc(expected.capacity());
        int previousBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] previousPack = new int[PACK_PARAMETERS.length];
        for (int i = 0; i < PACK_PARAMETERS.length; i++) {
            previousPack[i] = GL11C.glGetInteger(PACK_PARAMETERS[i]);
        }
        try {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            for (int i = 0; i < PACK_PARAMETERS.length; i++) {
                GL11C.glPixelStorei(PACK_PARAMETERS[i], i == 0 ? 1 : 0);
            }
            GL45C.glGetTextureImage(tex, 0, GL30C.GL_RG_INTEGER, GL11C.GL_UNSIGNED_BYTE, actual);
            long mismatches = 0;
            for (int i = 0; i < expected.capacity(); i++) {
                if (expected.get(i) != actual.get(i)) mismatches++;
            }
            return mismatches;
        } finally {
            try {
                for (int i = 0; i < PACK_PARAMETERS.length; i++) {
                    GL11C.glPixelStorei(PACK_PARAMETERS[i], previousPack[i]);
                }
            } finally {
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousBuffer);
                MemoryUtil.memFree(actual);
            }
        }
    }

    public static synchronized void close() {
        SmoothBlocksAnimatedMetadata.clear();
        int old = textureId;
        textureId = 0;
        atlasWidth = 0;
        atlasHeight = 0;
        classifiedSprites = 0;
        classifiedTexels = 0;
        classifiedEdgeTexels = 0;
        animatedFallbackSprites = 0;
        sourceComparedTexels = 0;
        sourcePixelMismatches = 0;
        uvMismatchSprites = 0;
        overlappingTexels = 0;
        uploadMismatchedBytes = 0;
        sourceMode = "none";
        sourceTextureId = 0;
        if (old != 0) GlStateManager._deleteTexture(old);
    }

    public static int textureId() { return textureId; }

    public static boolean ready() { return textureId != 0 && atlasWidth > 0 && atlasHeight > 0; }

    public static double edgePercent() {
        long total = classifiedTexels;
        return total == 0 ? 0.0 : 100.0 * classifiedEdgeTexels / total;
    }

    public static String sourceMode() { return sourceMode; }

    public static long uvMismatchSprites() { return uvMismatchSprites; }

    public static long animatedSprites() { return animatedFallbackSprites; }

    public static long overlappingTexels() { return overlappingTexels; }

    public static long uploadMismatchedBytes() { return uploadMismatchedBytes; }

    public static int sourceTextureId() { return sourceTextureId; }

    public static double sourceMismatchPercent() {
        long total = sourceComparedTexels;
        return total == 0 ? 0.0 : 100.0 * sourcePixelMismatches / total;
    }

    public static String debugString() {
        return "ready=" + ready()
                + " source=" + sourceMode
                + " sourceMismatch=" + String.format(java.util.Locale.ROOT, "%.3f%%", sourceMismatchPercent())
                + " atlas=" + atlasWidth + "x" + atlasHeight
                + " staticSprites=" + classifiedSprites
                + " texels=" + classifiedTexels
                + " edge=" + String.format(java.util.Locale.ROOT, "%.1f%%", edgePercent())
                + " uvBad=" + uvMismatchSprites
                + " overlap=" + overlappingTexels
                + " uploadDiff=" + uploadMismatchedBytes
                + " animatedLinear=" + animatedFallbackSprites
                + " animatedMetaBytes=" + SmoothBlocksAnimatedMetadata.preparedBytes()
                + " buildMs=" + String.format(java.util.Locale.ROOT, "%.2f", buildNanos / 1_000_000.0);
    }

    record AtlasSnapshot(int width, int height, int[] argb, int textureId) {}
}
