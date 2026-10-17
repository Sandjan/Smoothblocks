package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksEntityXbrzGpuBridge;
import de.oai.smoothblocks.SmoothBlocksShaderPatch;
import de.oai.smoothblocks.SmoothBlocksXbrzGpuBridge;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Terrain patching follows Iris AST transforms; entity calls are marked before transforms and receive helpers afterward. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher")
public abstract class IrisTransformPatcherMixin {
    // Injection orders 900/1100 pass the program name to the fragment hook without sharing concurrent compilations.
    private static final ThreadLocal<String> smoothblocks$vanillaProgramName = new ThreadLocal<>();

    @Inject(method = "patchSodium", at = @At("RETURN"), cancellable = true, remap = false)
    private static void smoothblocks$finishSodiumOutput(CallbackInfoReturnable<Map<?, ?>> cir) {
        smoothblocks$patchOutput(cir, IrisTransformPatcherMixin::smoothblocks$isFragmentStage,
                SmoothBlocksShaderPatch::patchIrisTerrainFragmentPostTransform,
                SmoothBlocksXbrzGpuBridge::clearProgramCache);
    }

    @ModifyVariable(
            method = "patchVanilla",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            order = 900,
            remap = false
    )
    private static String smoothblocks$captureVanillaProgramName(String name) {
        smoothblocks$vanillaProgramName.set(name);
        return name;
    }

    @ModifyVariable(
            method = "patchVanilla",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 5,
            order = 1100,
            remap = false
    )
    private static String smoothblocks$markVanillaEntityFragment(String fragment) {
        String name = smoothblocks$vanillaProgramName.get();
        smoothblocks$vanillaProgramName.remove();
        return SmoothBlocksShaderPatch.markIrisEntitySamples(name, fragment);
    }

    @Inject(method = "patchVanilla", at = @At("RETURN"), cancellable = true, remap = false)
    private static void smoothblocks$finishVanillaOutput(CallbackInfoReturnable<Map<?, ?>> cir) {
        smoothblocks$patchOutput(cir, key -> true, SmoothBlocksShaderPatch::finishIrisEntityFragment,
                SmoothBlocksEntityXbrzGpuBridge::clearProgramCache);
    }

    private static void smoothblocks$patchOutput(CallbackInfoReturnable<Map<?, ?>> callback,
                                                 Predicate<Object> selected, UnaryOperator<String> patch,
                                                 Runnable invalidatePrograms) {
        Map<?, ?> original = callback.getReturnValue();
        if (original == null || original.isEmpty()) return;
        Map<Object, Object> output = null;
        for (Map.Entry<?, ?> entry : original.entrySet()) {
            if (!selected.test(entry.getKey()) || !(entry.getValue() instanceof String source)) continue;
            String patched = patch.apply(source);
            if (patched.equals(source)) continue;
            // Copy lazily; Iris retains ownership of the input map and unchanged stage values.
            if (output == null) output = new LinkedHashMap<>(original);
            output.put(entry.getKey(), patched);
        }
        if (output == null) return;
        invalidatePrograms.run();
        callback.setReturnValue(output);
    }

    private static boolean smoothblocks$isFragmentStage(Object key) {
        if (key == null) return false;
        return key.toString().toUpperCase(java.util.Locale.ROOT).contains("FRAGMENT");
    }
}
