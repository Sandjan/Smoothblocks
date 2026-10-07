package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksClient;
import de.oai.smoothblocks.SmoothBlocksShaderPatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Patches Sodium's bundled default chunk fragment shader before ShaderParser compiles it. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader")
public abstract class SodiumShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true, remap = false)
    private static void smoothblocks$patchSource(CallbackInfoReturnable<String> cir) {
        String source = cir.getReturnValue();
        String patched = SmoothBlocksShaderPatch.patchSodiumFragment(source);
        if (patched != null && !patched.equals(source)) {
            SmoothBlocksClient.noteDefaultSodiumShaderPatched();
            cir.setReturnValue(patched);
        }
    }
}
