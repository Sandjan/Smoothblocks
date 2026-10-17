package de.oai.smoothblocks;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;

/** Private Minecraft 26.1.2 fields resolve once per type; pixels use the typed ARGB API. */
final class SmoothBlocksSpriteAccess {
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> fields = new HashMap<>();
            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                fields.put(field.getName(), field);
            }
            return Map.copyOf(fields);
        }
    };

    record Frame(int index, int duration) {}
    record Animation(Object identity, List<Frame> frames, int columns, boolean interpolate) {}

    private SmoothBlocksSpriteAccess() {}

    static NativeImage image(SpriteContents contents) {
        return (NativeImage) field(contents, "originalImage");
    }

    static Animation animation(SpriteContents contents) {
        Object animation = field(contents, "animatedTexture");
        if (animation == null) throw new IllegalStateException("Animated sprite has no animation info");
        List<?> frames = (List<?>) field(animation, "frames");
        List<Frame> sequence = frames.stream()
                .map(frame -> new Frame((int) field(frame, "index"), (int) field(frame, "time"))).toList();
        return new Animation(animation, sequence, (int) field(animation, "frameRowSize"),
                (boolean) field(animation, "interpolateFrames"));
    }

    static Object animationInfo(Object state) {
        return field(state, "animationInfo");
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = FIELDS.get(owner.getClass()).get(name);
            if (field == null) throw new NoSuchFieldException(name);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Cannot read Minecraft field " + owner.getClass().getName() + "." + name, failure);
        }
    }
}
