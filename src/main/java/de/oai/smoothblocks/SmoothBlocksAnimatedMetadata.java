package de.oai.smoothblocks;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.BufferUtils;

import static de.oai.smoothblocks.SmoothBlocksMetadataFormat.*;

/** Frame maps stay immutable on the GPU; the render thread changes only their four-byte address. */
public final class SmoothBlocksAnimatedMetadata {
    private static final int WEIGHT_SCALE = 1000;
    private static final ByteBuffer POINTER = BufferUtils.createByteBuffer(Integer.BYTES);
    private static final Map<Object, Entry> STATES = new IdentityHashMap<>();
    private static Prepared active = Prepared.empty();

    private SmoothBlocksAnimatedMetadata() {}

    private record FrameKey(int current, int next, int weight) {}

    /** Keys own classifier output, which is never mutated after insertion. */
    private record MetadataKey(short[] values) {
        @Override public int hashCode() { return Arrays.hashCode(values); }
        @Override public boolean equals(Object other) {
            return other instanceof MetadataKey key && Arrays.equals(values, key.values);
        }
    }

    private record FrameOffsets(int duration, int[] addresses) {
        int at(int tick) {
            if (addresses.length == 1) return addresses[0];
            return addresses[duration > WEIGHT_SCALE ? weight(tick, duration) : tick];
        }
    }

    private static final class Entry {
        final int slot;
        final List<FrameOffsets> frames;
        SmoothBlocksXbrzCore.Result staticResult;
        int current;

        Entry(int slot, List<FrameOffsets> frames, SmoothBlocksXbrzCore.Result staticResult) {
            this.slot = slot;
            this.frames = frames;
            this.staticResult = staticResult;
            current = frames.getFirst().at(0);
        }
    }

    /** Pending CPU generation; production publishes it only after its matching texture has uploaded. */
    static final class Prepared {
        private final int width;
        private final int height;
        private final short[] data;
        private final Map<SpriteContents, Entry> sprites;
        private final Map<Object, Entry> animations;

        private Prepared(int width, int height, short[] data,
                         Map<SpriteContents, Entry> sprites, Map<Object, Entry> animations) {
            this.width = width;
            this.height = height;
            this.data = data;
            this.sprites = sprites;
            this.animations = animations;
        }

        static Prepared empty() {
            return new Prepared(0, 0, new short[0], new IdentityHashMap<>(), new IdentityHashMap<>());
        }

        short[] data() { return data; }

        int marker(TextureAtlasSprite sprite) {
            Entry entry = sprites.get(sprite.contents());
            return entry == null ? ANIMATED_LINEAR : SmoothBlocksMetadataFormat.marker(entry.slot);
        }

        SmoothBlocksXbrzCore.Result staticResult(TextureAtlasSprite sprite) {
            Entry entry = sprites.get(sprite.contents());
            return entry == null ? null : entry.staticResult;
        }
    }

    /** Bounds metadata growth before retaining each map, including descriptor storage. */
    private static final class Storage {
        private final int limit;
        private final int descriptorCells;
        private final List<short[]> chunks = new ArrayList<>();
        private int cursor;

        Storage(int spriteCount, int limit) {
            this.limit = limit;
            descriptorCells = Math.multiplyExact(spriteCount, DESCRIPTOR_CELLS);
            cursor = descriptorCells;
            requireCapacity(0);
        }

        void requireCapacity(int cells) {
            if ((long) cursor + cells > limit) {
                throw new IllegalArgumentException("Block metadata exceeds GPU capacity");
            }
        }

        int append(short[] metadata) {
            requireCapacity(metadata.length);
            int address = cursor;
            cursor = Math.addExact(cursor, metadata.length);
            chunks.add(metadata);
            return address;
        }

        short[] finish(short[] descriptors) {
            short[] data = Arrays.copyOf(descriptors, cursor);
            int address = descriptorCells;
            for (short[] chunk : chunks) {
                System.arraycopy(chunk, 0, data, address, chunk.length);
                address += chunk.length;
            }
            return data;
        }
    }

    private static final class FrameBuilder {
        private final TextureAtlasSprite sprite;
        private final SmoothBlocksSpriteAccess.Animation animation;
        private final Storage storage;
        private final Map<Integer, int[]> pixels = new HashMap<>();
        private final Map<FrameKey, Integer> combinations = new HashMap<>();
        private final Map<MetadataKey, Integer> identical = new HashMap<>();

        FrameBuilder(TextureAtlasSprite sprite, SmoothBlocksSpriteAccess.Animation animation, Storage storage) {
            this.sprite = sprite;
            this.animation = animation;
            this.storage = storage;
        }

        List<FrameOffsets> build() {
            List<FrameOffsets> frames = new ArrayList<>();
            for (int index = 0; index < animation.frames().size(); index++) frames.add(buildFrame(index));
            return List.copyOf(frames);
        }

        private FrameOffsets buildFrame(int frame) {
            var current = animation.frames().get(frame);
            int next = animation.frames().get((frame + 1) % animation.frames().size()).index();
            int duration = current.duration();
            if (duration <= 0) throw new IllegalArgumentException("Invalid animation duration");
            int steps = animation.interpolate() ? Math.min(duration, WEIGHT_SCALE + 1) : 1;
            int[] addresses = new int[steps];
            for (int step = 0; step < steps; step++) {
                int blendWeight = !animation.interpolate() ? 0
                        : duration > WEIGHT_SCALE ? step : weight(step, duration);
                addresses[step] = address(current.index(), next, blendWeight);
            }
            return new FrameOffsets(duration, addresses);
        }

        private int address(int current, int next, int blendWeight) {
            FrameKey key = new FrameKey(current, blendWeight == 0 ? current : next, blendWeight);
            Integer cached = combinations.get(key);
            if (cached != null) return cached;
            int[] source = framePixels(current);
            if (blendWeight != 0) source = blend(source, framePixels(next), blendWeight);
            var contents = sprite.contents();
            short[] metadata = SmoothBlocksXbrzCore.classifyArgb(
                    contents.width(), contents.height(), source, periodic(sprite)).metadata();
            // Deduplication is per sprite, matching the original address and storage contract.
            MetadataKey signature = new MetadataKey(metadata);
            Integer address = identical.get(signature);
            if (address == null) {
                address = storage.append(metadata);
                identical.put(signature, address);
            }
            combinations.put(key, address);
            return address;
        }

        private int[] framePixels(int index) {
            return pixels.computeIfAbsent(index, frame -> {
                var contents = sprite.contents();
                int width = contents.width(), height = contents.height();
                return SmoothBlocksPixels.read(SmoothBlocksSpriteAccess.image(contents),
                        frame % animation.columns() * width, frame / animation.columns() * height, width, height);
            });
        }
    }

    public static void clear() {
        active = Prepared.empty();
        STATES.clear();
    }

    static boolean eligible(TextureAtlasSprite sprite) {
        return sprite.contents().isAnimated() && blockSprite(sprite);
    }

    private static boolean blockSprite(TextureAtlasSprite sprite) {
        String path = sprite.contents().name().getPath();
        return path.startsWith("block/")
                && !path.startsWith("block/water_") && !path.startsWith("block/lava_")
                && !path.equals("block/nether_portal")
                && !path.matches("block/(soul_)?(fire_[01]|campfire_fire)");
    }

    /** Transparent sprites and excluded effects retain isolated boundaries. */
    static boolean periodic(TextureAtlasSprite sprite) {
        return blockSprite(sprite) && sprite.transparency().isOpaque();
    }

    /** Harness convenience; production uses build/publish around GPU upload. */
    static short[] prepare(SpriteLoader.Preparations preparations) {
        return prepare(preparations, null);
    }

    static short[] prepare(SpriteLoader.Preparations preparations, int[] atlasPixels) {
        Prepared prepared = build(preparations, atlasPixels, Integer.MAX_VALUE);
        publish(prepared);
        return prepared.data;
    }

    static Prepared build(SpriteLoader.Preparations preparations, int[] atlasPixels, int maxCells) {
        var sprites = preparations.regions().values().stream().filter(s -> eligible(s) || periodic(s)).toList();
        if (sprites.size() > MAX_SPRITES) throw new IllegalArgumentException("Too many block sprites");
        return new Builder(preparations.width(), preparations.height(), atlasPixels, sprites.size(), maxCells).build(sprites);
    }

    /** One resource-load transaction owns all temporary addresses, maps and descriptor writes. */
    private static final class Builder {
        private final int width;
        private final int height;
        private final int[] pixels;
        private final Storage storage;
        private final short[] descriptors;
        private final Map<SpriteContents, Entry> entries = new IdentityHashMap<>();
        private final Map<Object, Entry> animations = new IdentityHashMap<>();

        Builder(int width, int height, int[] pixels, int spriteCount, int maxCells) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
            storage = new Storage(spriteCount, maxCells);
            descriptors = new short[Math.multiplyExact(spriteCount, DESCRIPTOR_CELLS)];
        }

        Prepared build(List<TextureAtlasSprite> sprites) {
            for (TextureAtlasSprite sprite : sprites) addSprite(sprite);
            return new Prepared(width, height, storage.finish(descriptors), entries, animations);
        }

        private void addSprite(TextureAtlasSprite sprite) {
            var contents = sprite.contents();
            int spriteWidth = contents.width(), spriteHeight = contents.height();
            if (spriteWidth <= 0 || spriteWidth > WIDTH_MASK || spriteHeight <= 0 || spriteHeight > 0xFFFF) {
                throw new IllegalArgumentException("Sprite dimensions exceed descriptor format");
            }
            Entry entry = contents.isAnimated() ? animatedEntry(sprite) : staticEntry(sprite);
            entries.put(contents, entry);
            writeDescriptor(sprite, entry);
        }

        private Entry animatedEntry(TextureAtlasSprite sprite) {
            var animation = SmoothBlocksSpriteAccess.animation(sprite.contents());
            if (animation.frames().isEmpty()) throw new IllegalArgumentException("Empty block animation");
            Entry entry = new Entry(entries.size(), new FrameBuilder(sprite, animation, storage).build(), null);
            animations.put(animation.identity(), entry);
            return entry;
        }

        private Entry staticEntry(TextureAtlasSprite sprite) {
            var contents = sprite.contents();
            int spriteWidth = contents.width(), spriteHeight = contents.height();
            storage.requireCapacity(Math.multiplyExact(spriteWidth, spriteHeight));
            int x = Math.round(sprite.getU0() * width), y = Math.round(sprite.getV0() * height);
            int[] source = pixels == null
                    ? SmoothBlocksPixels.read(SmoothBlocksSpriteAccess.image(contents), 0, 0, spriteWidth, spriteHeight)
                    : SmoothBlocksPixels.crop(pixels, width, x, y, spriteWidth, spriteHeight);
            var classified = SmoothBlocksXbrzCore.classifyArgb(spriteWidth, spriteHeight, source, true);
            var frame = new FrameOffsets(1, new int[]{storage.append(classified.metadata())});
            return new Entry(entries.size(), List.of(frame), classified);
        }

        private void writeDescriptor(TextureAtlasSprite sprite, Entry entry) {
            int base = entry.slot * DESCRIPTOR_CELLS;
            descriptors[base] = (short) Math.round(sprite.getU0() * width);
            descriptors[base + 1] = (short) Math.round(sprite.getV0() * height);
            descriptors[base + 2] = (short) (sprite.contents().width() | (periodic(sprite) ? WRAP_FLAG : 0));
            descriptors[base + 3] = (short) sprite.contents().height();
            descriptors[base + ADDRESS_CELL] = (short) entry.current;
            descriptors[base + ADDRESS_CELL + 1] = (short) (entry.current >>> 16);
        }
    }

    static void publish(Prepared prepared) {
        prepared.sprites.values().forEach(entry -> entry.staticResult = null);
        active = new Prepared(prepared.width, prepared.height, new short[0], prepared.sprites, prepared.animations);
        STATES.clear();
    }

    static int marker(TextureAtlasSprite sprite) { return active.marker(sprite); }

    static int frameAddress(TextureAtlasSprite sprite, int frame, int tick) {
        return active.sprites.get(sprite.contents()).frames.get(frame).at(tick);
    }

    /** Runs after Minecraft uploads the corresponding frame; null entries cache excluded animations too. */
    public static void update(Object state, int frame, int tick) {
        if (!STATES.containsKey(state)) {
            STATES.put(state, active.animations.get(SmoothBlocksSpriteAccess.animationInfo(state)));
        }
        Entry entry = STATES.get(state);
        if (entry == null) return;
        int address = entry.frames.get(frame).at(tick);
        if (address == entry.current) return;
        POINTER.put(0, (byte) address).put(1, (byte) (address >>> 8))
                .put(2, (byte) (address >>> 16)).put(3, (byte) (address >>> 24));
        SmoothBlocksXbrzMetadata.updatePointer(
                entry.slot * DESCRIPTOR_CELLS + ADDRESS_CELL, active.width, active.height, POINTER);
        entry.current = address;
    }

    private static int weight(int tick, int duration) {
        return (int) ((float) tick / duration * WEIGHT_SCALE);
    }

    /** Matches Minecraft's quantized interpolation and rounded ARGB channels. */
    private static int[] blend(int[] a, int[] b, int weight) {
        int[] output = new int[a.length];
        float fraction = weight / (float) WEIGHT_SCALE;
        for (int i = 0; i < output.length; i++) {
            int pixel = 0;
            for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
                float value = ((a[i] >>> shift) & 255) * (1f - fraction)
                        + ((b[i] >>> shift) & 255) * fraction;
                pixel |= Math.round(value) << shift;
            }
            output[i] = pixel;
        }
        return output;
    }
}
