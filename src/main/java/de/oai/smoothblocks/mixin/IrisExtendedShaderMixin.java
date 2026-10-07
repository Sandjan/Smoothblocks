package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksEntityXbrzGpuBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iris rebinds ExtendedShader programs during setup, so write our mode after that final bind too. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader")
public abstract class IrisExtendedShaderMixin {
    @Inject(method = "iris$setupState", at = @At("RETURN"), remap = false, require = 0)
    private void smoothblocks$configureEntityMode(CallbackInfo ci) {
        SmoothBlocksEntityXbrzGpuBridge.configureActiveEntityProgram();
    }
}
