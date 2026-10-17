package de.oai.smoothblocks;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

/** Render-thread owner of RG8UI metadata: atlas-aligned base rows, then sprite descriptors and frame maps. */
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

    private static int textureId;
    private static int sourceTextureId;

    /** CPU/GPU generations become visible together; any failed rebuild invalidates both. */
    public static void rebuild(TextureAtlas atlas, SpriteLoader.Preparations preparations) {
        RenderSystem.assertOnRenderThread();
        boolean published = false;
        try {
            AtlasSnapshot snapshot = captureLevel0(atlas.getTexture());
            if (snapshot.width != preparations.width() || snapshot.height != preparations.height()) {
                throw new IllegalArgumentException("GPU atlas dimensions differ from sprite preparations");
            }
            int maxSize = GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE);
            long capacity = Math.min((long) snapshot.width * (maxSize - snapshot.height),
                    Integer.MAX_VALUE / Short.BYTES - (long) snapshot.width * snapshot.height);
            var prepared = SmoothBlocksAnimatedMetadata.build(
                    preparations, snapshot.argb, (int) Math.max(0, capacity));
            int height = Math.addExact(snapshot.height,
                    Math.toIntExact(((long) prepared.data().length + snapshot.width - 1) / snapshot.width));
            ByteBuffer data = assembleAtlas(snapshot, preparations, prepared, height);
            int replacement;
            try {
                replacement = uploadMetadata(snapshot.width, height, data);
            } finally {
                MemoryUtil.memFree(data);
            }
            int previous = textureId;
            textureId = replacement;
            sourceTextureId = snapshot.textureId;
            SmoothBlocksAnimatedMetadata.publish(prepared);
            published = true;
            if (previous != 0) GlStateManager._deleteTexture(previous);
        } catch (RuntimeException failure) {
            System.err.println("[SmoothBlocks] Block metadata unavailable: " + failure);
        } finally {
            if (!published) close();
        }
    }

    private static ByteBuffer assembleAtlas(AtlasSnapshot snapshot, SpriteLoader.Preparations preparations,
                                           SmoothBlocksAnimatedMetadata.Prepared prepared, int height) {
        ByteBuffer data = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(snapshot.width, height), Short.BYTES));
        boolean complete = false;
        try {
            for (int cell = 0; cell < data.capacity() / Short.BYTES; cell++) {
                putMeta(data, cell * Short.BYTES, SmoothBlocksMetadataFormat.MISSING);
            }
            int base = snapshot.width * snapshot.height;
            short[] extra = prepared.data();
            for (int cell = 0; cell < extra.length; cell++) putMeta(data, (base + cell) * Short.BYTES, extra[cell]);
            for (TextureAtlasSprite sprite : preparations.regions().values()) writeSprite(data, snapshot, sprite, prepared);
            complete = true;
            return data;
        } finally {
            if (!complete) MemoryUtil.memFree(data);
        }
    }

    private static void writeSprite(ByteBuffer data, AtlasSnapshot snapshot, TextureAtlasSprite sprite,
                                    SmoothBlocksAnimatedMetadata.Prepared prepared) {
        int width = sprite.contents().width(), height = sprite.contents().height();
        int x = Math.round(sprite.getU0() * snapshot.width), y = Math.round(sprite.getV0() * snapshot.height);
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || (long) x + width > snapshot.width || (long) y + height > snapshot.height) {
            throw new IllegalArgumentException("Sprite outside block atlas: " + sprite.contents().name());
        }
        if (sprite.contents().isAnimated()) {
            fillRect(data, snapshot.width, x, y, width, height, prepared.marker(sprite));
            return;
        }
        SmoothBlocksXbrzCore.Result classified = prepared.staticResult(sprite);
        boolean periodic = classified != null;
        if (classified == null) {
            int[] pixels = SmoothBlocksPixels.crop(snapshot.argb, snapshot.width, x, y, width, height);
            classified = SmoothBlocksXbrzCore.classifyArgb(width, height, pixels);
        }
        short[] metadata = classified.metadata();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                // Only border indirection needs wrap bounds; interior texels use direct states.
                boolean border = column == 0 || row == 0 || column == width - 1 || row == height - 1;
                int value = periodic && border ? prepared.marker(sprite) : metadata[row * width + column];
                putMeta(data, ((y + row) * snapshot.width + x + column) * Short.BYTES, value);
            }
        }
    }

    /** Requires a live 2D RGBA8 GL texture on the render thread; restores pack/PBO state and returns ARGB pixels. */
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

    /** RG8UI stores the unsigned descriptor low byte first, independent of native byte order. */
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

    /** Shared upload with explicit pixel-store preservation; caller owns the GL texture. */
    static int uploadMetadata(int width, int height, ByteBuffer data) {
        int texture = GlStateManager._genTexture();
        boolean complete = false;
        try {
            uploadInto(texture, width, height, data);
            complete = true;
            return texture;
        } finally {
            if (!complete) GlStateManager._deleteTexture(texture);
        }
    }

    static int uploadMetadata(int width, int height, short[] metadata) {
        ByteBuffer bytes = MemoryUtil.memAlloc(Math.multiplyExact(metadata.length, Short.BYTES));
        try {
            for (short value : metadata) bytes.put((byte) value).put((byte) (value >>> Byte.SIZE));
            return uploadMetadata(width, height, bytes.flip());
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    private static void uploadInto(int tex, int width, int height, ByteBuffer data) {
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
            // RG8UI uses tightly packed two-byte texels; inherited row/skip state would corrupt atlas addressing.
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

    }

    /** Writes the active atlas address in two RG8UI cells; cell is relative to the appended metadata area. */
    static void updatePointer(int cell, int width, int baseHeight, ByteBuffer address) {
        updatePointer(textureId, cell, width, baseHeight, address);
    }

    /** Render-thread scratch state; splits the four-byte address when its cells cross a metadata row. */
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

    public static void close() {
        SmoothBlocksAnimatedMetadata.clear();
        int previous = textureId;
        textureId = 0;
        sourceTextureId = 0;
        if (previous != 0) GlStateManager._deleteTexture(previous);
    }

    public static int textureId() { return textureId; }
    public static int sourceTextureId() { return sourceTextureId; }
    public static boolean ready() { return textureId != 0; }

    record AtlasSnapshot(int width, int height, int[] argb, int textureId) {}
}
