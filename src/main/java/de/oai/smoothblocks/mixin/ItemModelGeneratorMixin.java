package de.oai.smoothblocks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.oai.smoothblocks.SmoothBlocksSideCollector;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.ItemModelGenerator;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemModelGenerator.class)
public abstract class ItemModelGeneratorMixin {
    // Wrap the caller, not the body Sodium replaces. Front/back faces are already collected here.
    @WrapOperation(method = "bakeExtrudedSprite", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/model/cuboid/ItemModelGenerator;bakeSideFaces(Lnet/minecraft/client/resources/model/geometry/QuadCollection$Builder;Lnet/minecraft/client/resources/model/ModelBaker$Interner;Lnet/minecraft/client/renderer/block/dispatch/ModelState;Lnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;)V"))
    private static void smoothblocks$collectSides(QuadCollection.Builder builder, ModelBaker.Interner interner,
                                                 ModelState state, BakedQuad.MaterialInfo material,
                                                 Operation<Void> original) {
        var collector = (SmoothBlocksSideCollector) (Object) builder;
        collector.smoothblocks$collectSides(true);
        try {
            original.call(builder, interner, state, material);
        } finally {
            collector.smoothblocks$collectSides(false);
        }
    }
}
