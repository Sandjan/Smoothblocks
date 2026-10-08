package de.oai.smoothblocks;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.BufferUtils;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;

/** Frame data is immutable on the GPU. Animation changes update only a four-byte address. */
public final class SmoothBlocksAnimatedMetadata {
    private SmoothBlocksAnimatedMetadata() {}
    private static final Map<SpriteContents, Entry> SPRITES = new IdentityHashMap<>();
    private static final Map<Object, Entry> ANIMATIONS = new IdentityHashMap<>();
    private static final Map<Object, Entry> STATES = new IdentityHashMap<>();
    private static final ByteBuffer POINTER = BufferUtils.createByteBuffer(4);
    private static Field stateAnimation;
    private static int atlasWidth, atlasHeight;
    private static long preparedBytes;
    private static final class Entry {
        final int slot;
        final int[][] offsets;
        int current;
        Entry(int slot, int[][] offsets) {
            this.slot = slot; this.offsets = offsets; this.current = offsets[0][0];
        }
    }
    private record FrameKey(int current, int next, int weight) {}
    private record MetadataKey(short[] values) {
        @Override public int hashCode() { return Arrays.hashCode(values); }
        @Override public boolean equals(Object other) {
            return other instanceof MetadataKey key && Arrays.equals(values, key.values);
        }
    }

    public static void clear() { SPRITES.clear(); ANIMATIONS.clear(); STATES.clear(); preparedBytes = 0; }
    static long preparedBytes() { return preparedBytes; }

    /** Only block sprites; explicitly excluded effects keep the existing linear sentinel. */
    static boolean eligible(TextureAtlasSprite sprite) {
        String path = sprite.contents().name().getPath();
        return sprite.contents().isAnimated() && path.startsWith("block/")
                && !path.startsWith("block/water_") && !path.startsWith("block/lava_")
                && !path.equals("block/nether_portal")
                && !path.matches("block/(soul_)?(fire_[01]|campfire_fire)");
    }

    static short[] prepare(SpriteLoader.Preparations preparations) {
        clear(); atlasWidth = preparations.width(); atlasHeight = preparations.height();
        var sprites = preparations.regions().values().stream().filter(SmoothBlocksAnimatedMetadata::eligible).toList();
        if (sprites.size() >= 8191) throw new IllegalArgumentException("Too many animated block sprites");
        int cursor = Math.multiplyExact(sprites.size(), 5);
        var chunks = new ArrayList<short[]>();
        var descriptors = new short[cursor];
        for (var sprite : sprites) {
            try {
                var contents = sprite.contents();
                Object animation = field(contents, "animatedTexture");
                var frames = (List<?>) field(animation, "frames");
                int columns = (int) field(animation, "frameRowSize");
                boolean interpolate = (boolean) field(animation, "interpolateFrames");
                NativeImage image = (NativeImage) field(contents, "originalImage");
                int w = contents.width(), h = contents.height();
                var pixels = new HashMap<Integer, int[]>();
                var dedup = new HashMap<FrameKey, Integer>();
                var identical = new HashMap<MetadataKey, Integer>();
                int[][] offsets = new int[frames.size()][];
                for (int f = 0; f < frames.size(); f++) {
                    int index = (int) field(frames.get(f), "index");
                    int next = (int) field(frames.get((f + 1) % frames.size()), "index");
                    int time = (int) field(frames.get(f), "time");
                    offsets[f] = new int[interpolate ? time : 1];
                    for (int t = 0; t < offsets[f].length; t++) {
                        // Matches Minecraft's quantized SpriteAnimationInfo draw index.
                        int weight = interpolate ? (int) ((float) t / time * 1000f) : 0;
                        var key = new FrameKey(index, weight == 0 ? index : next, weight);
                        Integer offset = dedup.get(key);
                        if (offset == null) {
                            int[] a = pixels.computeIfAbsent(index, i -> read(image, i, columns, w, h));
                            int[] source = a;
                            if (weight != 0) {
                                int[] b = pixels.computeIfAbsent(next, i -> read(image, i, columns, w, h));
                                source = blend(a, b, weight);
                            }
                            short[] meta = SmoothBlocksXbrzCore.classifyArgb(w, h, source).metadata();
                            var signature = new MetadataKey(meta);
                            offset = identical.get(signature);
                            if (offset == null) {
                                offset = cursor; cursor = Math.addExact(cursor, meta.length);
                                chunks.add(meta); identical.put(signature, offset);
                            }
                            dedup.put(key, offset);
                        }
                        offsets[f][t] = offset;
                    }
                }
                int slot = SPRITES.size(), d = slot * 5;
                descriptors[d] = (short) Math.round(sprite.getU0() * atlasWidth);
                descriptors[d + 1] = (short) Math.round(sprite.getV0() * atlasHeight);
                descriptors[d + 2] = (short) w;
                descriptors[d + 3] = (short) offsets[0][0];
                descriptors[d + 4] = (short) (offsets[0][0] >>> 16);
                var entry = new Entry(slot, offsets);
                SPRITES.put(contents, entry); ANIMATIONS.put(animation, entry);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot prepare animated block " + sprite.contents().name(), e);
            }
        }
        short[] extra = new short[cursor];
        System.arraycopy(descriptors, 0, extra, 0, descriptors.length);
        cursor = descriptors.length;
        for (short[] chunk : chunks) { System.arraycopy(chunk, 0, extra, cursor, chunk.length); cursor += chunk.length; }
        preparedBytes = (long) extra.length * 2;
        return extra;
    }

    static int marker(TextureAtlasSprite sprite) {
        Entry entry = SPRITES.get(sprite.contents());
        return entry == null ? 0xFFFF : (entry.slot << 3) | 7; // xBRZ corner states only use 0..5.
    }

    static int frameAddress(TextureAtlasSprite sprite, int frame, int subFrame) {
        return SPRITES.get(sprite.contents()).offsets[frame][subFrame];
    }

    public static void update(Object state, int frame, int subFrame) {
        if (!STATES.containsKey(state)) {
            try {
                if (stateAnimation == null) {
                    stateAnimation = state.getClass().getDeclaredField("animationInfo");
                    stateAnimation.setAccessible(true);
                }
                STATES.put(state, ANIMATIONS.get(stateAnimation.get(state)));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        Entry entry = STATES.get(state);
        if (entry == null) return;
        int[] offsets = entry.offsets[frame];
        int offset = offsets[offsets.length == 1 ? 0 : subFrame];
        if (offset == entry.current) return;
        POINTER.put(0, (byte) offset).put(1, (byte) (offset >>> 8))
                .put(2, (byte) (offset >>> 16)).put(3, (byte) (offset >>> 24));
        int cell = entry.slot * 5 + 3;
        // The two address texels may straddle a row; update separately in that case.
        SmoothBlocksXbrzMetadata.updatePointer(cell, atlasWidth, atlasHeight, POINTER);
        entry.current = offset;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static int[] read(NativeImage image, int frame, int columns, int w, int h) {
        int[] pixels = new int[w * h]; int ox = frame % columns * w, oy = frame / columns * h;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixels[y * w + x] = image.getPixel(ox + x, oy + y);
        return pixels;
    }
    private static int[] blend(int[] a, int[] b, int weight) {
        int[] out = new int[a.length];
        for (int i = 0; i < out.length; i++) {
            int pixel = 0;
            for (int shift = 0; shift < 32; shift += 8) {
                float v = ((a[i] >>> shift) & 255) * (1f - weight / 1000f) + ((b[i] >>> shift) & 255) * (weight / 1000f);
                pixel |= Math.round(v) << shift;
            }
            out[i] = pixel;
        }
        return out;
    }
}
