package de.oai.smoothblocks;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureContents;

/** Fills hidden edge RGB for entity/armor textures; alpha and visible pixels stay unchanged. */
public final class SmoothBlocksAlphaEdgeRepair {
    private static final int MAX_DIMENSION = 4096;

    private SmoothBlocksAlphaEdgeRepair() {}

    /** Best effort: reports partial writes on failure and lets VM Errors propagate. */
    public static int repair(TextureContents contents) {
        if (contents == null || contents.image() == null) return 0;
        NativeImage image = contents.image();
        int repaired = 0;
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) return 0;
            // Snapshot prevents repaired colors from propagating beyond the original visible edge.
            int[] source = SmoothBlocksPixels.read(image, 0, 0, width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if ((source[y * width + x] >>> 24) != 0) continue;
                    int neighbor = strongestNeighbor(source, width, height, x, y);
                    if ((neighbor >>> 24) == 0) continue;
                    image.setPixel(x, y, neighbor & 0x00FFFFFF);
                    repaired++;
                }
            }
        } catch (RuntimeException failure) {
            System.err.println("[SmoothBlocks] Alpha edge repair stopped after " + repaired + " pixels: " + failure);
        }
        return repaired;
    }

    /** Equal-alpha ties keep row-major scan order, preserving the existing edge colors. */
    private static int strongestNeighbor(int[] pixels, int width, int height, int x, int y) {
        int best = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int row = y + dy;
            if (row < 0 || row >= height) continue;
            for (int dx = -1; dx <= 1; dx++) {
                int column = x + dx;
                if (column < 0 || column >= width) continue;
                int candidate = pixels[row * width + column];
                if ((candidate >>> 24) > (best >>> 24)) best = candidate;
            }
        }
        return best;
    }
}
