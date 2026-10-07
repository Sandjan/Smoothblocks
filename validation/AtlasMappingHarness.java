package de.oai.smoothblocks;

import java.util.Arrays;
import java.util.Random;

/**
 * Dependency-free stress test for the exact atlas addressing/byte packing used by
 * SmoothBlocksXbrzMetadata + the GLSL RG8UI decoder.  No Minecraft classes needed.
 */
public final class AtlasMappingHarness {
    private static final int MISSING = 0xFFFE;
    private static final int ANIMATED = 0xFFFF;

    public static void main(String[] args) {
        Random rnd = new Random(0x5842525AL);
        long staticTexels = 0;
        long animatedTexels = 0;
        long checks = 0;

        for (int iteration = 0; iteration < 500; iteration++) {
            // Non-power-of-two sizes make accidental row/stride assumptions visible.
            int aw = 97 + rnd.nextInt(137);
            int ah = 89 + rnd.nextInt(131);
            byte[] rg = new byte[aw * ah * 2];
            for (int i = 0; i < aw * ah; i++) put(rg, i, MISSING);
            boolean[] occupied = new boolean[aw * ah];

            for (int sprite = 0; sprite < 24; sprite++) {
                int sw = 2 + rnd.nextInt(14);
                int sh = 2 + rnd.nextInt(14);
                int[] pos = findFree(rnd, occupied, aw, ah, sw, sh);
                if (pos == null) continue;
                int ox = pos[0], oy = pos[1];
                mark(occupied, aw, ox, oy, sw, sh);

                // Reproduce TextureAtlasSprite UV -> level-0 texel reconstruction via float.
                float u0 = (float) ox / (float) aw;
                float v0 = (float) oy / (float) ah;
                int reconstructedX = Math.round(u0 * aw);
                int reconstructedY = Math.round(v0 * ah);
                require(reconstructedX == ox && reconstructedY == oy,
                        "UV origin reconstruction drift");

                boolean animated = rnd.nextInt(7) == 0;
                if (animated) {
                    for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
                        put(rg, (oy + y) * aw + ox + x, ANIMATED);
                        animatedTexels++;
                    }
                } else {
                    int[] argb = new int[sw * sh];
                    for (int i = 0; i < argb.length; i++) {
                        // Pixel-art-ish palette, not continuous random noise.
                        int p = rnd.nextInt(8);
                        int r = (p * 37) & 255, g = (p * 71) & 255, b = (p * 113) & 255;
                        argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    short[] expected = SmoothBlocksXbrzCore.classifyArgb(sw, sh, argb).metadata();
                    for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
                        int local = y * sw + x;
                        int atlasIndex = (oy + y) * aw + ox + x;
                        put(rg, atlasIndex, expected[local] & 0xFFFF);
                        staticTexels++;
                    }

                    // Emulate fragment shader: floor(uv * textureSize), then r | g<<8.
                    for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
                        double u = (ox + x + 0.5) / (double) aw;
                        double v = (oy + y + 0.5) / (double) ah;
                        int tx = Math.max(0, Math.min(aw - 1, (int) Math.floor(u * aw)));
                        int ty = Math.max(0, Math.min(ah - 1, (int) Math.floor(v * ah)));
                        int actual = get(rg, ty * aw + tx);
                        int wanted = expected[y * sw + x] & 0xFFFF;
                        require(actual == wanted,
                                "metadata association mismatch expected=" + wanted + " actual=" + actual);
                        checks++;
                    }
                }
            }

            // Pixels never assigned to sprite content must remain MISSING.
            for (int i = 0; i < occupied.length; i++) {
                if (!occupied[i]) require(get(rg, i) == MISSING, "padding overwritten");
            }
        }

        System.out.println("PASS atlas-map iterations=500 checks=" + checks
                + " staticTexels=" + staticTexels + " animatedTexels=" + animatedTexels);
    }

    private static int[] findFree(Random rnd, boolean[] used, int aw, int ah, int sw, int sh) {
        for (int tries = 0; tries < 200; tries++) {
            int ox = rnd.nextInt(aw - sw + 1);
            int oy = rnd.nextInt(ah - sh + 1);
            boolean ok = true;
            for (int y = 0; y < sh && ok; y++) for (int x = 0; x < sw; x++) {
                if (used[(oy + y) * aw + ox + x]) { ok = false; break; }
            }
            if (ok) return new int[]{ox, oy};
        }
        return null;
    }

    private static void mark(boolean[] used, int aw, int ox, int oy, int sw, int sh) {
        for (int y = 0; y < sh; y++) Arrays.fill(used, (oy + y) * aw + ox, (oy + y) * aw + ox + sw, true);
    }

    private static void put(byte[] rg, int texel, int value) {
        int p = texel * 2;
        rg[p] = (byte) (value & 255);
        rg[p + 1] = (byte) ((value >>> 8) & 255);
    }

    private static int get(byte[] rg, int texel) {
        int p = texel * 2;
        return (rg[p] & 255) | ((rg[p + 1] & 255) << 8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
