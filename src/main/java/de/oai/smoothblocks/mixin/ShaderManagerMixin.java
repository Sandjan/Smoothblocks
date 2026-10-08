package de.oai.smoothblocks.mixin;

import com.mojang.blaze3d.shaders.ShaderType;
import de.oai.smoothblocks.SmoothBlocksClient;
import de.oai.smoothblocks.SmoothBlocksShaderPatch;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import de.oai.smoothblocks.SmoothBlocksEntityXbrzGpuBridge;

/** Adds entity xBRZ to Mojang's entity fragment shader on the normal non-Iris shader path. */
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public abstract class ShaderManagerMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void smoothblocks$invalidatePrograms(CallbackInfo ci) {
        SmoothBlocksEntityXbrzGpuBridge.clearProgramCache();
    }

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private void smoothblocks$patchEntityFragment(Identifier id, ShaderType type,
                                                   CallbackInfoReturnable<String> cir) {
        if (type != ShaderType.FRAGMENT) return;
        String source = cir.getReturnValue();
        if (source == null) return;

        String patched = SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect(String.valueOf(id), source);
        if (!patched.equals(source)) {
            SmoothBlocksClient.noteDefaultEntityShaderPatched();
            cir.setReturnValue(patched);
        }
    }
}
