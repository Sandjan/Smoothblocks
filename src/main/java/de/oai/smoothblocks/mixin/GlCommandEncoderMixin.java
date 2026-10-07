package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksEntityXbrzGpuBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sets SmoothBlocks' scalar entity mode after Minecraft has bound a draw program. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
    @Inject(method = "trySetup", at = @At("RETURN"), require = 0)
    private void smoothblocks$configureEntityMode(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            SmoothBlocksEntityXbrzGpuBridge.configureActiveEntityProgram();
        }
    }
}
