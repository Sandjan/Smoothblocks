package de.oai.smoothblocks;

/** Marks only the artificial walls produced by Minecraft's generated-item baker. */
public interface SmoothBlocksGeneratedSide {
    void smoothblocks$markGeneratedSide();
    boolean smoothblocks$isGeneratedSide();

    /** Requires a quad carrying this mixin; retain the complete baked geometry for GUI draws. */
    static boolean shouldOmit(Object quad) {
        return SmoothBlocksRenderScope.isWorld()
                && ((SmoothBlocksGeneratedSide) quad).smoothblocks$isGeneratedSide();
    }
}
