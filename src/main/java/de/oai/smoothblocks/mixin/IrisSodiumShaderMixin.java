package de.oai.smoothblocks.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import de.oai.smoothblocks.SmoothBlocksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalDouble;

/**
 * Iris 1.11.4 uses IrisSamplers.getTerrainCache() for the actual Sodium terrain sampler.
 * Intercepting the returned sampler is more reliable than targeting SodiumShader.setupState,
 * because it hits the shared cache method directly.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.samplers.IrisSamplers")
public abstract class IrisSodiumShaderMixin {
    private static final GpuSampler[] SMOOTHBLOCKS_LINEAR = new GpuSampler[33];
    private static final GpuSampler[] SMOOTHBLOCKS_NEAREST = new GpuSampler[33];

    @Inject(method = "getTerrainCache", at = @At("RETURN"), cancellable = true, remap = false)
    private static void smoothblocks$replaceIrisTerrain(int requestedAnisotropy,
                                                          CallbackInfoReturnable<GpuSampler> cir) {
        GpuSampler original = cir.getReturnValue();
        SmoothBlocksClient.noteIrisSodiumTerrainSampler(original);

        if (!SmoothBlocksClient.isEnabled()) {
            SmoothBlocksClient.noteIrisSodiumTerrainApplied(original);
            return;
        }

        FilterMode filter = SmoothBlocksClient.getSelectedFilter();
        int anisotropy = Math.max(1, Math.min(32, original.getMaxAnisotropy()));
        GpuSampler[] cache = filter == FilterMode.LINEAR ? SMOOTHBLOCKS_LINEAR : SMOOTHBLOCKS_NEAREST;
        GpuSampler replacement = cache[anisotropy];

        if (replacement == null) {
            synchronized (cache) {
                replacement = cache[anisotropy];
                if (replacement == null) {
                    replacement = RenderSystem.getDevice().createSampler(
                            AddressMode.CLAMP_TO_EDGE,
                            AddressMode.CLAMP_TO_EDGE,
                            filter,
                            filter,
                            anisotropy,
                            OptionalDouble.empty()
                    );
                    cache[anisotropy] = replacement;
                }
            }
        }

        SmoothBlocksClient.noteIrisSodiumTerrainApplied(replacement);
        cir.setReturnValue(replacement);
    }
}
