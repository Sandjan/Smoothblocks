package de.oai.smoothblocks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import de.oai.smoothblocks.SmoothBlocksGeneratedSide;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Fabric Renderer API emits baked quads without VertexConsumer.putBakedQuad. */
@Pseudo
@Mixin(targets = {
        "net.caffeinemc.mods.sodium.client.render.frapi.render.ItemRenderContext",
        "net.caffeinemc.mods.sodium.client.render.frapi.render.ExtendedItemFeatureRenderer"})
public abstract class SodiumItemRenderContextMixin {
    @Coerce
    @WrapOperation(method = {"bufferQuads", "prepareSubmit"}, remap = false, at = @At(value = "INVOKE",
            target = "Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter;fromBakedQuad(Lnet/minecraft/client/resources/model/geometry/BakedQuad;)Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter;"))
    private Object smoothblocks$checkWall(@Coerce Object emitter, BakedQuad quad, Operation<Object> original,
                                         @Share("omitWall") LocalBooleanRef omit) {
        omit.set(SmoothBlocksGeneratedSide.shouldOmit(quad));
        return omit.get() ? emitter : original.call(emitter, quad);
    }

    @Coerce
    @WrapOperation(method = {"bufferQuads", "prepareSubmit"}, remap = false, at = @At(value = "INVOKE",
            target = "Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter;emit()Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter;"))
    private Object smoothblocks$omitWall(@Coerce Object emitter, Operation<Object> original,
                                        @Share("omitWall") LocalBooleanRef omit) {
        return omit.get() ? emitter : original.call(emitter);
    }
}
