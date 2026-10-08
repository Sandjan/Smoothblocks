package de.oai.smoothblocks;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTexture;

/** State of the actual backend pass supplied to trySetup, never a global 'last bind'. */
public interface SmoothBlocksDrawState {
    RenderPipeline smoothblocks$pipeline();
    GpuTexture smoothblocks$diffuse();
}
