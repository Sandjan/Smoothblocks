package de.oai.smoothblocks;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;

import static de.oai.smoothblocks.SmoothBlocksMetadataFormat.*;

/** Resource-time cache; draw-time lookups never allocate, classify or read pixels back. */
public final class SmoothBlocksTextureMetadata {
    record Region(int x, int y, int width, int height, boolean animated) {}

    private static final class Entry {
        final List<Region> regions; // null means a standalone texture
        int metadata;
        int[] pixels;

        Entry(List<Region> regions) { this.regions = regions; }

        void close() {
            if (metadata != 0) GlStateManager._deleteTexture(metadata);
            metadata = 0;
            pixels = null;
        }
    }

    private static final Map<GpuTexture, Entry> ENTRIES = new IdentityHashMap<>();
    private static final Set<GpuTexture> DIRTY = Collections.newSetFromMap(new IdentityHashMap<>());
    private static long builds;
    private static int fallback;

    private SmoothBlocksTextureMetadata() {}

    /** A valid integer sampler remains mandatory even for GUI draws with reconstruction disabled. */
    public static int fallbackTexture() {
        if (fallback == 0) fallback = SmoothBlocksXbrzMetadata.uploadMetadata(1, 1, new short[]{(short) MISSING});
        return fallback;
    }

    public static void close() {
        ENTRIES.values().forEach(Entry::close);
        ENTRIES.clear();
        DIRTY.clear();
        if (fallback != 0) GlStateManager._deleteTexture(fallback);
        fallback = 0;
    }

    /** Requires the render thread; captures the uploaded level 0 immediately. */
    public static void registerTexture(GpuTexture texture) {
        if (texture == null || texture.isClosed() || ENTRIES.containsKey(texture)) return;
        Entry entry = new Entry(null);
        ENTRIES.put(texture, entry);
        rebuild(texture, entry);
    }

    public static void registerAtlas(TextureAtlas atlas, SpriteLoader.Preparations preparations) {
        GpuTexture texture = atlas.getTexture();
        forget(texture);
        int width = preparations.width(), height = preparations.height();
        List<Region> regions = preparations.regions().values().stream().map(sprite -> new Region(
                Math.round(sprite.getU0() * width), Math.round(sprite.getV0() * height),
                sprite.contents().width(), sprite.contents().height(), sprite.contents().isAnimated())).toList();
        Entry entry = new Entry(regions);
        ENTRIES.put(texture, entry);
        rebuild(texture, entry);
    }

    /** Coalesces standalone uploads; atlas animation follows its separate frame/sentinel policy. */
    public static void changed(GpuTexture texture) {
        Entry entry = ENTRIES.get(texture);
        if (entry != null && entry.regions == null) DIRTY.add(texture);
    }

    public static void refreshChanged() {
        for (GpuTexture texture : DIRTY) {
            Entry entry = ENTRIES.get(texture);
            if (entry != null) rebuild(texture, entry);
        }
        DIRTY.clear();
    }

    public static int textureId(GpuTexture texture) {
        if (texture == null || texture.isClosed()) return 0;
        if (texture instanceof com.mojang.blaze3d.opengl.GlTexture gl
                && gl.glId() == SmoothBlocksXbrzMetadata.sourceTextureId()) {
            return SmoothBlocksXbrzMetadata.textureId();
        }
        Entry entry = ENTRIES.get(texture);
        return entry == null || DIRTY.contains(texture) ? 0 : entry.metadata;
    }

    /** GPU replacement stays here; CPU atlas policy can be checked without a GL context. */
    private static void rebuild(GpuTexture texture, Entry entry) {
        try {
            var image = SmoothBlocksXbrzMetadata.captureLevel0(texture);
            if (entry.metadata != 0 && Arrays.equals(entry.pixels, image.argb())) return;
            short[] metadata = classify(image.width(), image.height(), image.argb(), entry.regions);
            int replacement = SmoothBlocksXbrzMetadata.uploadMetadata(image.width(), image.height(), metadata);
            entry.close();
            entry.metadata = replacement;
            entry.pixels = entry.regions == null ? image.argb() : null;
            builds++;
        } catch (RuntimeException failure) {
            entry.close();
            System.err.println("[SmoothBlocks] Metadata unavailable for " + texture.getLabel() + ": " + failure);
        }
    }

    /** Each sprite is isolated; padding stays missing and animated regions retain the linear sentinel. */
    static short[] classify(int width, int height, int[] pixels, List<Region> regions) {
        if (regions == null) return SmoothBlocksXbrzCore.classifyArgb(width, height, pixels).metadata();
        short[] metadata = new short[Math.multiplyExact(width, height)];
        Arrays.fill(metadata, (short) MISSING);
        for (Region region : regions) writeRegion(metadata, width, height, pixels, region);
        return metadata;
    }

    private static void writeRegion(short[] output, int width, int height, int[] pixels, Region region) {
        if (region.x < 0 || region.y < 0 || region.width <= 0 || region.height <= 0
                || (long) region.x + region.width > width || (long) region.y + region.height > height) {
            throw new IllegalArgumentException("Sprite outside atlas");
        }
        short[] local = region.animated ? null : SmoothBlocksXbrzCore.classifyArgb(
                region.width, region.height, SmoothBlocksPixels.crop(
                        pixels, width, region.x, region.y, region.width, region.height)).metadata();
        for (int row = 0; row < region.height; row++) {
            int offset = (region.y + row) * width + region.x;
            if (region.animated) Arrays.fill(output, offset, offset + region.width, (short) ANIMATED_LINEAR);
            else System.arraycopy(local, row * region.width, output, offset, region.width);
        }
    }

    public static void forget(GpuTexture texture) {
        DIRTY.remove(texture);
        Entry entry = ENTRIES.remove(texture);
        if (entry != null) entry.close();
    }

    static long buildCount() { return builds; }
}
