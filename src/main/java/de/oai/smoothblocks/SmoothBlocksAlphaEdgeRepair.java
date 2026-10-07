package de.oai.smoothblocks;

import net.minecraft.client.renderer.texture.TextureContents;

import java.lang.reflect.Method;

/**
 * Removes black bilinear fringes from entity textures by copying RGB from the
 * nearest visible pixel into fully transparent edge pixels while preserving alpha.
 *
 * This is deliberately applied only to individual entity/armor textures, never to
 * atlases, GUI textures, fonts, particles, or weather.
 */
public final class SmoothBlocksAlphaEdgeRepair {
    private SmoothBlocksAlphaEdgeRepair() {}

    private static volatile Accessor accessor;

    public static int repair(TextureContents contents) {
        if (contents == null || contents.image() == null) return 0;

        Accessor a = getAccessor(contents.image());
        if (a == null) return 0;

        try {
            int width = (Integer) a.width.invoke(contents.image());
            int height = (Integer) a.height.invoke(contents.image());
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return 0;

            // Snapshot the pixels first. That prevents a newly repaired pixel from
            // influencing the color copied into another transparent pixel.
            int[] source = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    source[y * width + x] = (Integer) a.getPixel.invoke(contents.image(), x, y);
                }
            }

            int repaired = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int dst = source[y * width + x];
                    if (((dst >>> 24) & 0xFF) != 0) continue;

                    int best = 0;
                    int bestAlpha = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;

                            int candidate = source[ny * width + nx];
                            int alpha = (candidate >>> 24) & 0xFF;
                            if (alpha > bestAlpha) {
                                bestAlpha = alpha;
                                best = candidate;
                            }
                        }
                    }

                    if (bestAlpha > 0) {
                        // Keep the transparent destination alpha exactly as-is and
                        // copy only RGB. This fixes the color bleed without changing
                        // entity shape or transparency.
                        int repairedPixel = (dst & 0xFF000000) | (best & 0x00FFFFFF);
                        a.setPixel.invoke(contents.image(), x, y, repairedPixel);
                        repaired++;
                    }
                }
            }
            return repaired;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Accessor getAccessor(Object image) {
        Accessor current = accessor;
        if (current != null && current.type == image.getClass()) return current;
        synchronized (SmoothBlocksAlphaEdgeRepair.class) {
            current = accessor;
            if (current != null && current.type == image.getClass()) return current;
            try {
                Class<?> type = image.getClass();
                Method width = type.getMethod("getWidth");
                Method height = type.getMethod("getHeight");
                Method getPixel = find(type, "getPixel", "getPixelRGBA", "getPixelColor");
                Method setPixel = find(type, "setPixel", "setPixelRGBA", "setPixelColor");
                if (getPixel == null || setPixel == null) return null;
                width.setAccessible(true);
                height.setAccessible(true);
                getPixel.setAccessible(true);
                setPixel.setAccessible(true);
                current = new Accessor(type, width, height, getPixel, setPixel);
                accessor = current;
                return current;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static Method find(Class<?> type, String... names) {
        for (String name : names) {
            try {
                if (name.startsWith("get")) return type.getMethod(name, int.class, int.class);
                return type.getMethod(name, int.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private record Accessor(Class<?> type, Method width, Method height, Method getPixel, Method setPixel) {}
}
