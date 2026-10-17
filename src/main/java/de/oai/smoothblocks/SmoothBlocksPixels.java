package de.oai.smoothblocks;

import com.mojang.blaze3d.platform.NativeImage;

/** Row-major ARGB operations shared by sprite extraction and CPU atlas construction. */
final class SmoothBlocksPixels {
    private SmoothBlocksPixels() {}

    static int[] read(NativeImage image, int x, int y, int width, int height) {
        int[] pixels = new int[Math.multiplyExact(width, height)];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                pixels[row * width + column] = image.getPixel(x + column, y + row);
            }
        }
        return pixels;
    }

    static int[] crop(int[] source, int stride, int x, int y, int width, int height) {
        int[] pixels = new int[Math.multiplyExact(width, height)];
        for (int row = 0; row < height; row++) {
            System.arraycopy(source, (y + row) * stride + x, pixels, row * width, width);
        }
        return pixels;
    }
}
