package de.oai.smoothblocks.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import de.oai.smoothblocks.SmoothBlocksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Iris uses its own terrain cache; replacements belong to the mod's shared sampler owner. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.samplers.IrisSamplers")
public abstract class IrisSodiumShaderMixin {
    @Inject(method = "getTerrainCache", at = @At("RETURN"), cancellable = true, remap = false)
    private static void smoothblocks$replaceIrisTerrain(int requestedAnisotropy,
                                                        CallbackInfoReturnable<GpuSampler> callback) {
        callback.setReturnValue(SmoothBlocksClient.chooseIrisTerrainSampler(callback.getReturnValue()));
    }
}
