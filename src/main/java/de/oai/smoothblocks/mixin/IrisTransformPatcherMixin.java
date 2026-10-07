package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksClient;
import de.oai.smoothblocks.SmoothBlocksShaderPatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Terrain is patched only after Iris has completed its Sodium/texture AST transforms.
 * Entity compatibility keeps the older two-phase path for now because it does not use
 * the terrain metadata atlas and is unrelated to the atlas-association bug.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher")
public abstract class IrisTransformPatcherMixin {
    private static final ThreadLocal<String> smoothblocks$vanillaProgramName = new ThreadLocal<>();

    @Inject(method = "patchSodium", at = @At("RETURN"), cancellable = true, remap = false)
    private static void smoothblocks$finishSodiumOutput(CallbackInfoReturnable<Map<?, ?>> cir) {
        Map<?, ?> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) return;

        Map<Object, Object> patchedMap = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<?, ?> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (smoothblocks$isFragmentStage(entry.getKey()) && value instanceof String source) {
                String patched = SmoothBlocksShaderPatch.patchIrisTerrainFragmentPostTransform(source);
                if (!patched.equals(source)) {
                    value = patched;
                    changed = true;
                    SmoothBlocksClient.noteIrisTerrainShaderPatched();
                }
            }
            patchedMap.put(entry.getKey(), value);
        }
        if (changed) cir.setReturnValue((Map) patchedMap);
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
        Map<?, ?> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) return;

        Map<Object, Object> patchedMap = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<?, ?> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String source) {
                String patched = SmoothBlocksShaderPatch.finishIrisEntityFragment(source);
                if (!patched.equals(source)) {
                    value = patched;
                    changed = true;
                    SmoothBlocksClient.noteIrisEntityShaderPatched();
                }
            }
            patchedMap.put(entry.getKey(), value);
        }
        if (changed) cir.setReturnValue((Map) patchedMap);
    }

    private static boolean smoothblocks$isFragmentStage(Object key) {
        if (key == null) return false;
        return key.toString().toUpperCase(java.util.Locale.ROOT).contains("FRAGMENT");
    }
}
