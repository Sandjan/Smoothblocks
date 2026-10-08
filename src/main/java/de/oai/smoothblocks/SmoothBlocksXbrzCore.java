package de.oai.smoothblocks;

/**
 * Dependency-free CPU implementation of xBRZ-Freescale pass 0 with an
 * alpha-aware extension that is exactly identical for fully opaque input.
 *
 * <p>This is intentionally kept algorithmically identical to the validated
 * {@code FreescalePreprocessor} in the standalone xBRZ lab. It operates on
 * Java ARGB pixels (0xAARRGGBB) and packs one unsigned 16-bit descriptor per
 * source texel: TL/TR/BR/BL xBRZ corner states in 3 bits each plus four hard
 * image/sprite boundary bits.</p>
 */
public final class SmoothBlocksXbrzCore {
    private SmoothBlocksXbrzCore() {}

    public static final int TL_SHIFT = 0;
    public static final int TR_SHIFT = 3;
    public static final int BR_SHIFT = 6;
    public static final int BL_SHIFT = 9;
    public static final int HARD_LEFT   = 1 << 12;
    public static final int HARD_RIGHT  = 1 << 13;
    public static final int HARD_TOP    = 1 << 14;
    public static final int HARD_BOTTOM = 1 << 15;

    private static final int BLEND_NONE = 0;
    private static final int BLEND_NORMAL = 1;
    private static final int BLEND_DOMINANT = 2;

    private static final double EQUAL_COLOR_TOLERANCE = 30.0;
    private static final double STEEP_DIRECTION_THRESHOLD = 2.2;
    private static final double DOMINANT_DIRECTION_THRESHOLD = 3.6;

    private static final double WR = 0.2627;
    private static final double WG = 0.6780;
    private static final double WB = 0.0593;
    private static final double SCALE_B = 0.5 / (1.0 - WB);
    private static final double SCALE_R = 0.5 / (1.0 - WR);

    public record Stats(long texels, long texelsWithEdge, long cornerStates,
                        long cornerOnly, long diagonal, long shallow,
                        long steep, long shallowSteep) {
        public double edgeTexelPercent() {
            return texels == 0 ? 0.0 : 100.0 * texelsWithEdge / texels;
        }
    }

    public record Result(short[] metadata, Stats stats) {}

    /** Standalone-lab pass-0 classification; opaque input remains bit-identical to the validated lab. */
    public static Result classifyArgb(int width, int height, int[] argb) {
        return classifyArgb(width, height, argb, false);
    }

    public static Result classifyArgb(int width, int height, int[] argb, boolean periodic) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image size");
        if (argb == null || argb.length != width * height) {
            throw new IllegalArgumentException("Pixel array size mismatch");
        }

        short[] out = new short[argb.length];
        long edgeTexels = 0, corners = 0, cornerOnly = 0, diagonal = 0, shallow = 0, steep = 0, both = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = get(argb, width, height, periodic, x - 1, y - 1);
                int b = get(argb, width, height, periodic, x,     y - 1);
                int c = get(argb, width, height, periodic, x + 1, y - 1);
                int d = get(argb, width, height, periodic, x - 1, y);
                int e = get(argb, width, height, periodic, x,     y);
                int f = get(argb, width, height, periodic, x + 1, y);
                int g = get(argb, width, height, periodic, x - 1, y + 1);
                int h = get(argb, width, height, periodic, x,     y + 1);
                int i = get(argb, width, height, periodic, x + 1, y + 1);

                int[] blend = new int[4]; // TL, TR, BR, BL

                if (!((exactVisible(e, f) && exactVisible(h, i)) || (exactVisible(e, h) && exactVisible(f, i)))) {
                    double distHF = distVisible(g, e) + distVisible(e, c)
                            + distVisible(get(argb, width, height, periodic, x, y + 2), i)
                            + distVisible(i, get(argb, width, height, periodic, x + 2, y))
                            + 4.0 * distVisible(h, f);
                    double distEI = distVisible(d, h)
                            + distVisible(h, get(argb, width, height, periodic, x + 1, y + 2))
                            + distVisible(b, f)
                            + distVisible(f, get(argb, width, height, periodic, x + 2, y + 1))
                            + 4.0 * distVisible(e, i);
                    boolean dominant = DOMINANT_DIRECTION_THRESHOLD * distHF < distEI;
                    blend[2] = (distHF < distEI && !exactVisible(e, f) && !exactVisible(e, h))
                            ? (dominant ? BLEND_DOMINANT : BLEND_NORMAL) : BLEND_NONE;
                }

                if (!((exactVisible(d, e) && exactVisible(g, h)) || (exactVisible(d, g) && exactVisible(e, h)))) {
                    double distGE = distVisible(get(argb, width, height, periodic, x - 2, y + 1), d)
                            + distVisible(d, b)
                            + distVisible(get(argb, width, height, periodic, x - 1, y + 2), h)
                            + distVisible(h, f) + 4.0 * distVisible(g, e);
                    double distDH = distVisible(get(argb, width, height, periodic, x - 2, y), g)
                            + distVisible(g, get(argb, width, height, periodic, x, y + 2))
                            + distVisible(a, e) + distVisible(e, i) + 4.0 * distVisible(d, h);
                    boolean dominant = DOMINANT_DIRECTION_THRESHOLD * distDH < distGE;
                    blend[3] = (distGE > distDH && !exactVisible(e, d) && !exactVisible(e, h))
                            ? (dominant ? BLEND_DOMINANT : BLEND_NORMAL) : BLEND_NONE;
                }

                if (!((exactVisible(b, c) && exactVisible(e, f)) || (exactVisible(b, e) && exactVisible(c, f)))) {
                    double distEC = distVisible(d, b)
                            + distVisible(b, get(argb, width, height, periodic, x + 1, y - 2))
                            + distVisible(h, f)
                            + distVisible(f, get(argb, width, height, periodic, x + 2, y - 1))
                            + 4.0 * distVisible(e, c);
                    double distBF = distVisible(a, e) + distVisible(e, i)
                            + distVisible(get(argb, width, height, periodic, x, y - 2), c)
                            + distVisible(c, get(argb, width, height, periodic, x + 2, y))
                            + 4.0 * distVisible(b, f);
                    boolean dominant = DOMINANT_DIRECTION_THRESHOLD * distBF < distEC;
                    blend[1] = (distEC > distBF && !exactVisible(e, b) && !exactVisible(e, f))
                            ? (dominant ? BLEND_DOMINANT : BLEND_NORMAL) : BLEND_NONE;
                }

                if (!((exactVisible(a, b) && exactVisible(d, e)) || (exactVisible(a, d) && exactVisible(b, e)))) {
                    double distDB = distVisible(get(argb, width, height, periodic, x - 2, y), a)
                            + distVisible(a, get(argb, width, height, periodic, x, y - 2))
                            + distVisible(g, e) + distVisible(e, c) + 4.0 * distVisible(d, b);
                    double distAE = distVisible(get(argb, width, height, periodic, x - 2, y - 1), d)
                            + distVisible(d, h)
                            + distVisible(get(argb, width, height, periodic, x - 1, y - 2), b)
                            + distVisible(b, f) + 4.0 * distVisible(a, e);
                    boolean dominant = DOMINANT_DIRECTION_THRESHOLD * distDB < distAE;
                    blend[0] = (distDB < distAE && !exactVisible(e, d) && !exactVisible(e, b))
                            ? (dominant ? BLEND_DOMINANT : BLEND_NORMAL) : BLEND_NONE;
                }

                int br = stateBottomRight(blend, a,b,c,d,e,f,g,h,i);
                int bl = stateBottomLeft (blend, a,b,c,d,e,f,g,h,i);
                int tr = stateTopRight   (blend, a,b,c,d,e,f,g,h,i);
                int tl = stateTopLeft    (blend, a,b,c,d,e,f,g,h,i);

                int meta = tl | (tr << TR_SHIFT) | (br << BR_SHIFT) | (bl << BL_SHIFT);
                if (x == 0) meta |= HARD_LEFT;
                if (x == width - 1) meta |= HARD_RIGHT;
                if (y == 0) meta |= HARD_TOP;
                if (y == height - 1) meta |= HARD_BOTTOM;
                out[y * width + x] = (short) meta;

                int[] states = {tl,tr,br,bl};
                boolean any = false;
                for (int s : states) {
                    if (s == 0) continue;
                    any = true;
                    corners++;
                    switch (s) {
                        case 1 -> cornerOnly++;
                        case 2 -> diagonal++;
                        case 3 -> shallow++;
                        case 4 -> steep++;
                        case 5 -> both++;
                        default -> throw new AssertionError("Invalid packed state " + s);
                    }
                }
                if (any) edgeTexels++;
            }
        }

        Stats stats = new Stats((long) width * height, edgeTexels, corners,
                cornerOnly, diagonal, shallow, steep, both);
        return new Result(out, stats);
    }

    private static int stateBottomRight(int[] blend, int a,int b,int c,int d,int e,int f,int g,int h,int i) {
        int z = blend[2];
        boolean line = z == BLEND_DOMINANT || (z == BLEND_NORMAL &&
                !((blend[1] != BLEND_NONE && !similar(e, g))
                        || (blend[3] != BLEND_NONE && !similar(e, c))
                        || (similar(g,h) && similar(h,i) && similar(i,f) && similar(f,c) && !similar(e,i))));
        if (!line) return packedState(z, false, false, false);
        double distFG = distVisible(f,g), distHC = distVisible(h,c);
        boolean shallow = STEEP_DIRECTION_THRESHOLD * distFG <= distHC && !exactVisible(e,g) && !exactVisible(d,g);
        boolean steep   = STEEP_DIRECTION_THRESHOLD * distHC <= distFG && !exactVisible(e,c) && !exactVisible(b,c);
        return packedState(z, true, shallow, steep);
    }

    private static int stateBottomLeft(int[] blend, int a,int b,int c,int d,int e,int f,int g,int h,int i) {
        int w = blend[3];
        boolean line = w == BLEND_DOMINANT || (w == BLEND_NORMAL &&
                !((blend[2] != BLEND_NONE && !similar(e, a))
                        || (blend[0] != BLEND_NONE && !similar(e, i))
                        || (similar(a,d) && similar(d,g) && similar(g,h) && similar(h,i) && !similar(e,g))));
        if (!line) return packedState(w, false, false, false);
        double distHA = distVisible(h,a), distDI = distVisible(d,i);
        boolean shallow = STEEP_DIRECTION_THRESHOLD * distHA <= distDI && !exactVisible(e,a) && !exactVisible(b,a);
        boolean steep   = STEEP_DIRECTION_THRESHOLD * distDI <= distHA && !exactVisible(e,i) && !exactVisible(f,i);
        return packedState(w, true, shallow, steep);
    }

    private static int stateTopRight(int[] blend, int a,int b,int c,int d,int e,int f,int g,int h,int i) {
        int y = blend[1];
        boolean line = y == BLEND_DOMINANT || (y == BLEND_NORMAL &&
                !((blend[0] != BLEND_NONE && !similar(e, i))
                        || (blend[2] != BLEND_NONE && !similar(e, a))
                        || (similar(i,f) && similar(f,c) && similar(c,b) && similar(b,a) && !similar(e,c))));
        if (!line) return packedState(y, false, false, false);
        double distBI = distVisible(b,i), distFA = distVisible(f,a);
        boolean shallow = STEEP_DIRECTION_THRESHOLD * distBI <= distFA && !exactVisible(e,i) && !exactVisible(h,i);
        boolean steep   = STEEP_DIRECTION_THRESHOLD * distFA <= distBI && !exactVisible(e,a) && !exactVisible(d,a);
        return packedState(y, true, shallow, steep);
    }

    private static int stateTopLeft(int[] blend, int a,int b,int c,int d,int e,int f,int g,int h,int i) {
        int x = blend[0];
        boolean line = x == BLEND_DOMINANT || (x == BLEND_NORMAL &&
                !((blend[3] != BLEND_NONE && !similar(e, c))
                        || (blend[1] != BLEND_NONE && !similar(e, g))
                        || (similar(c,b) && similar(b,a) && similar(a,d) && similar(d,g) && !similar(e,a))));
        if (!line) return packedState(x, false, false, false);
        double distDC = distVisible(d,c), distBG = distVisible(b,g);
        boolean shallow = STEEP_DIRECTION_THRESHOLD * distDC <= distBG && !exactVisible(e,c) && !exactVisible(f,c);
        boolean steep   = STEEP_DIRECTION_THRESHOLD * distBG <= distDC && !exactVisible(e,g) && !exactVisible(h,g);
        return packedState(x, true, shallow, steep);
    }

    private static int packedState(int blend, boolean line, boolean shallow, boolean steep) {
        if (blend == BLEND_NONE) return 0;
        if (!line) return 1;
        return 2 + (shallow ? 1 : 0) + (steep ? 2 : 0);
    }

    private static int get(int[] argb, int width, int height, boolean periodic, int x, int y) {
        if (periodic) return argb[Math.floorMod(y, height) * width + Math.floorMod(x, width)];
        return getClamp(argb, width, height, x, y);
    }

    private static int getClamp(int[] argb, int width, int height, int x, int y) {
        if (x < 0) x = 0;
        else if (x >= width) x = width - 1;
        if (y < 0) y = 0;
        else if (y >= height) y = height - 1;
        return argb[y * width + x];
    }

    /**
     * Exact equality in visible (premultiplied-alpha) color space. For fully opaque
     * texels this is byte-for-byte the old RGB equality, preserving the validated
     * opaque xBRZ classifier. Fully transparent texels compare equal regardless of
     * hidden RGB so transparent-black padding cannot invent fake color edges.
     */
    private static boolean exactVisible(int p, int q) {
        int ap = (p >>> 24) & 0xFF;
        int aq = (q >>> 24) & 0xFF;
        if (ap != aq) return false;
        if (ap == 0) return true;
        if (ap == 255) return (p & 0x00FF_FFFF) == (q & 0x00FF_FFFF);

        return ((p >>> 16) & 0xFF) * ap == ((q >>> 16) & 0xFF) * aq
                && ((p >>> 8) & 0xFF) * ap == ((q >>> 8) & 0xFF) * aq
                && (p & 0xFF) * ap == (q & 0xFF) * aq;
    }

    private static boolean similar(int p, int q) {
        return distVisible(p, q) < EQUAL_COLOR_TOLERANCE;
    }

    /**
     * Established xBRZ alpha-aware distance (the same construction used by the Java
     * xBRZ reference implementation): interpolate RGB distance by the lower alpha and
     * add the alpha difference itself. This gives exactly the validated YCbCr distance
     * for two opaque texels, zero distance for two fully transparent texels regardless
     * of hidden RGB, and a strong edge for opaque/transparent silhouettes.
     */
    private static double distVisible(int p, int q) {
        int ap = (p >>> 24) & 0xFF;
        int aq = (q >>> 24) & 0xFF;
        double dr = ((p >>> 16) & 0xFF) - ((q >>> 16) & 0xFF);
        double dg = ((p >>> 8) & 0xFF) - ((q >>> 8) & 0xFF);
        double db = (p & 0xFF) - (q & 0xFF);
        double y = WR * dr + WG * dg + WB * db;
        double cb = SCALE_B * (db - y);
        double cr = SCALE_R * (dr - y);
        double rgbDistance = Math.sqrt(y * y + cb * cb + cr * cr);
        int lo = Math.min(ap, aq);
        return (lo / 255.0) * rgbDistance + Math.abs(ap - aq);
    }
}
