package de.oai.smoothblocks;

/** Render-thread scope. Shader names alone cannot distinguish world and inventory items. */
public final class SmoothBlocksRenderScope {
    private static boolean world;
    private SmoothBlocksRenderScope() {}
    public static void beginWorld() { world = true; }
    public static void endWorld() { world = false; }
    public static boolean isWorld() { return world; }
}
