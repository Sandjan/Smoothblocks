package de.oai.smoothblocks;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Minecraft adapter for the validated dependency-free xBRZ-Freescale pass-0 core.
 *
 * <p>No xBRZ decisions live here anymore. This class only extracts the sprite pixels,
 * normalizes them to Java ARGB and delegates byte-for-byte metadata generation to
 * {@link SmoothBlocksXbrzCore}. Keeping the algorithm in one pure-Java core prevents
 * the Minecraft implementation from drifting away from the standalone validation lab.</p>
 */
public final class SmoothBlocksXbrzClassifier {
    private SmoothBlocksXbrzClassifier() {}

    public static final int META_MISSING = 0xFFFE;
    public static final int META_ANIMATED_LINEAR = 0xFFFF;

    public static short[] classify(TextureAtlasSprite sprite) {
        return classifyResult(sprite).metadata();
    }

    public static SmoothBlocksXbrzCore.Result classifyResult(TextureAtlasSprite sprite) {
        int width = sprite.contents().width();
        int height = sprite.contents().height();
        int[] argb = readArgb(sprite.contents(), width, height);
        return SmoothBlocksXbrzCore.classifyArgb(width, height, argb);
    }

    public static int[] readArgb(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.contents() == null) {
            throw new IllegalArgumentException("sprite/contents is null");
        }
        return readArgb(sprite.contents(), sprite.contents().width(), sprite.contents().height());
    }

    static int[] readArgb(SpriteContents contents, int width, int height) {
        NativeImage image = originalImage(contents);
        PixelGetter getter = pixelGetter(image);
        if (image == null || getter == null) {
            throw new IllegalStateException("Cannot access SpriteContents original image on Minecraft 26.1.2");
        }

        int[] argb = new int[width * height];
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int raw = (Integer) getter.method.invoke(image, x, y);
                    argb[y * width + x] = getter.abgr ? abgrToArgb(raw) : raw;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed reading xBRZ source pixels", e);
        }
        return argb;
    }

    private static int abgrToArgb(int abgr) {
        return (abgr & 0xFF00FF00)
                | ((abgr & 0x00FF0000) >>> 16)
                | ((abgr & 0x000000FF) << 16);
    }

    private static NativeImage originalImage(SpriteContents contents) {
        if (contents == null) return null;
        try {
            // Minecraft 26.1.2 stores the original image as a private NativeImage field
            // but exposes no stable compile-time getter in this Fabric target. There is
            // one direct NativeImage field (the mip chain is NativeImage[]), so selecting
            // by exact field type reliably yields the original source image.
            for (Field field : SpriteContents.class.getDeclaredFields()) {
                if (field.getType() == NativeImage.class) {
                    field.setAccessible(true);
                    Object value = field.get(contents);
                    if (value instanceof NativeImage image) return image;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static PixelGetter pixelGetter(NativeImage image) {
        if (image == null) return null;

        // Modern NativeImage#getPixel returns ARGB. getPixelColor follows the modern
        // ARGB API naming. The old getPixelRGBA API returned the native ABGR-packed int,
        // so normalize that fallback explicitly before entering the validated core.
        PixelGetter getter = findPixelGetter(image.getClass(), "getPixel", false);
        if (getter != null) return getter;
        getter = findPixelGetter(image.getClass(), "getPixelColor", false);
        if (getter != null) return getter;
        return findPixelGetter(image.getClass(), "getPixelRGBA", true);
    }

    private static PixelGetter findPixelGetter(Class<?> type, String name, boolean abgr) {
        try {
            Method method = type.getMethod(name, int.class, int.class);
            method.setAccessible(true);
            return new PixelGetter(method, abgr);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private record PixelGetter(Method method, boolean abgr) {}
}
