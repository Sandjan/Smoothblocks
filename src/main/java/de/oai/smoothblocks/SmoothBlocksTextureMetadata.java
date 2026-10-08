package de.oai.smoothblocks;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Set;

/** Resource-time p0 cache. Draws only look up existing metadata; they never classify/read back. */
public final class SmoothBlocksTextureMetadata {
    private SmoothBlocksTextureMetadata() {}
    private record Region(int x, int y, int width, int height, boolean animated) {}
    private static final class Entry {
        final List<Region> regions; // null = standalone texture
        final boolean item;
        int metadata;
        int[] pixels;
        boolean dirty;
        Entry(List<Region> regions, boolean item) { this.regions = regions; this.item = item; }
    }
    private static final Map<GpuTexture, Entry> ENTRIES = new IdentityHashMap<>();
    private static final Set<GpuTexture> DIRTY = Collections.newSetFromMap(new IdentityHashMap<>());
    private static long builds;
    private static int fallback;

    public static int fallbackTexture() {
        if (fallback == 0) {
            ByteBuffer bytes = MemoryUtil.memAlloc(2);
            try {
                bytes.put(0, (byte) 0xFE).put(1, (byte) 0xFF);
                fallback = SmoothBlocksXbrzMetadata.uploadMetadata(1, 1, bytes);
            } finally { MemoryUtil.memFree(bytes); }
        }
        return fallback;
    }

    public static void close() {
        for (Entry entry : ENTRIES.values()) {
            if (entry.metadata != 0) GlStateManager._deleteTexture(entry.metadata);
        }
        ENTRIES.clear();
        DIRTY.clear();
        if (fallback != 0) GlStateManager._deleteTexture(fallback);
        fallback = 0;
    }

    public static void registerTexture(GpuTexture texture) {
        if (texture == null || texture.isClosed() || ENTRIES.containsKey(texture)) return;
        Entry entry = new Entry(null, false);
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
        Entry entry = new Entry(regions, TextureAtlas.LOCATION_ITEMS.equals(atlas.location()));
        ENTRIES.put(texture, entry);
        rebuild(texture, entry);
    }

    /** Called after upload/copy. Coalesce partial updates before the next world render. */
    public static void changed(GpuTexture texture) {
        Entry entry = ENTRIES.get(texture);
        // Atlases use the same animated-sprite sentinel policy as the terrain path.
        if (entry != null && entry.regions == null) {
            entry.dirty = true;
            DIRTY.add(texture);
        }
    }

    public static void refreshChanged() {
        for (GpuTexture texture : DIRTY) {
            Entry entry = ENTRIES.get(texture);
            if (entry != null) rebuild(texture, entry);
        }
        DIRTY.clear();
    }

    /** No allocation, readback or classification on this path. */
    public static int textureId(GpuTexture texture) {
        if (texture == null || texture.isClosed()) return 0;
        if (texture instanceof com.mojang.blaze3d.opengl.GlTexture gl
                && gl.glId() == SmoothBlocksXbrzMetadata.sourceTextureId()) {
            return SmoothBlocksXbrzMetadata.textureId();
        }
        Entry entry = ENTRIES.get(texture);
        return entry == null || entry.dirty ? 0 : entry.metadata;
    }

    public static boolean isItemAtlas(GpuTexture texture) {
        Entry entry = ENTRIES.get(texture);
        return entry != null && entry.item;
    }

    private static void rebuild(GpuTexture texture, Entry entry) {
        entry.dirty = false;
        try {
            var image = SmoothBlocksXbrzMetadata.captureLevel0(texture);
            if (entry.metadata != 0 && Arrays.equals(entry.pixels, image.argb())) return;
            short[] metadata;
            if (entry.regions == null) {
                metadata = SmoothBlocksXbrzCore.classifyArgb(image.width(), image.height(), image.argb()).metadata();
            } else {
                metadata = new short[Math.multiplyExact(image.width(), image.height())];
                Arrays.fill(metadata, (short) 0xFFFE);
                for (Region r : entry.regions) {
                    if (r.x < 0 || r.y < 0 || r.width <= 0 || r.height <= 0
                            || r.x + r.width > image.width() || r.y + r.height > image.height()) {
                        throw new IllegalArgumentException("Sprite outside atlas");
                    }
                    int[] crop = new int[r.width * r.height];
                    for (int y = 0; y < r.height; y++) {
                        System.arraycopy(image.argb(), (r.y + y) * image.width() + r.x, crop, y * r.width, r.width);
                    }
                    short[] local = r.animated ? null : SmoothBlocksXbrzCore.classifyArgb(r.width, r.height, crop).metadata();
                    for (int y = 0; y < r.height; y++) {
                        int offset = (r.y + y) * image.width() + r.x;
                        if (r.animated) Arrays.fill(metadata, offset, offset + r.width, (short) 0xFFFF);
                        else System.arraycopy(local, y * r.width, metadata, offset, r.width);
                    }
                }
            }
            ByteBuffer bytes = MemoryUtil.memAlloc(Math.multiplyExact(metadata.length, 2));
            try {
                for (short value : metadata) { bytes.put((byte) value); bytes.put((byte) (value >>> 8)); }
                bytes.flip();
                int replacement = SmoothBlocksXbrzMetadata.uploadMetadata(image.width(), image.height(), bytes);
                if (entry.metadata != 0) GlStateManager._deleteTexture(entry.metadata);
                entry.metadata = replacement;
                // Standalone dynamic textures can upload unchanged pixels; avoid reclassification.
                entry.pixels = entry.regions == null ? image.argb() : null;
                builds++;
            } finally { MemoryUtil.memFree(bytes); }
        } catch (RuntimeException failure) {
            if (entry.metadata != 0) GlStateManager._deleteTexture(entry.metadata);
            entry.metadata = 0;
            entry.pixels = null;
            System.err.println("[SmoothBlocks] Metadata unavailable for " + texture.getLabel() + ": " + failure);
        }
    }

    public static void forget(GpuTexture texture) {
        DIRTY.remove(texture);
        Entry entry = ENTRIES.remove(texture);
        if (entry != null && entry.metadata != 0) GlStateManager._deleteTexture(entry.metadata);
    }

    public static String stats() { return "entityMeta=" + ENTRIES.size() + " builds=" + builds; }
    static long buildCount() { return builds; }
}
