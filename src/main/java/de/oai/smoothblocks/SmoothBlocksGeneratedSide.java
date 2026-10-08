package de.oai.smoothblocks;

/** Marks only the artificial walls produced by Minecraft's generated-item baker. */
public interface SmoothBlocksGeneratedSide {
    void smoothblocks$markGeneratedSide();
    boolean smoothblocks$isGeneratedSide();
}
