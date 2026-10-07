package de.oai.smoothblocks.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import de.oai.smoothblocks.SmoothBlocksClient;
import de.oai.smoothblocks.SmoothBlocksXbrzGpuBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla/Sodium path without an Iris shaderpack: Sodium passes the terrain
 * sampler into ShaderChunkRenderer.begin(), which then reaches the default
 * Sodium chunk shader interface.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer")
public abstract class SodiumShaderChunkRendererMixin {
    @ModifyVariable(
            method = "begin",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private GpuSampler smoothblocks$replaceTerrainSampler(GpuSampler original) {
        return SmoothBlocksClient.chooseSodiumTerrainSampler(original);
    }

    @Inject(method = "begin", at = @At("TAIL"), remap = false)
    private void smoothblocks$configureXbrzProgram(CallbackInfo ci) {
        SmoothBlocksXbrzGpuBridge.configureActiveTerrainProgram();
    }

    @Inject(method = "end", at = @At("HEAD"), remap = false, require = 0)
    private void smoothblocks$releaseXbrzTexture(CallbackInfo ci) {
        SmoothBlocksXbrzGpuBridge.releaseMetadataTexture();
    }
}
