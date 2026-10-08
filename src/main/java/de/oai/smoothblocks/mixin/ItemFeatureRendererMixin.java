package de.oai.smoothblocks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.oai.smoothblocks.SmoothBlocksGeneratedSide;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {
    // Covers regular, glint and outline submissions. Original quads remain available to GUI/off modes.
    @WrapOperation(method = "renderItem", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"), require = 3)
    private void smoothblocks$omitGeneratedWalls(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad,
                                                QuadInstance instance, Operation<Void> original) {
        if (SmoothBlocksGeneratedSide.shouldOmit(quad)) return;
        original.call(consumer, pose, quad, instance);
    }
}
