package de.oai.smoothblocks.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import de.oai.smoothblocks.SmoothBlocksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Handles non-Sodium world textures such as entities and held items.
 * Terrain is intentionally NOT filtered here because Sodium has its own
 * dedicated terrainSampler which is handled by SodiumDefaultChunkRendererMixin.
 */
@Mixin(RenderPass.class)
public abstract class RenderPassMixin {
    @Unique
    private RenderPipeline smoothblocks$currentPipeline;

    @Inject(method = "setPipeline", at = @At("HEAD"))
    private void smoothblocks$capturePipeline(RenderPipeline pipeline, CallbackInfo ci) {
        this.smoothblocks$currentPipeline = pipeline;
        SmoothBlocksClient.notePipeline(pipeline);
    }

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void smoothblocks$observeTextureBind(String name, GpuTextureView textureView, GpuSampler sampler, CallbackInfo ci) {
        SmoothBlocksClient.observeBind(this.smoothblocks$currentPipeline, name, textureView, sampler);
    }

    @ModifyVariable(method = "bindTexture", at = @At("HEAD"), argsOnly = true)
    private GpuSampler smoothblocks$replaceEntitySampler(GpuSampler original) {
        return SmoothBlocksClient.chooseWorldEntitySampler(this.smoothblocks$currentPipeline, original);
    }
}
