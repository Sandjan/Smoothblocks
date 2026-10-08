package de.oai.smoothblocks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.oai.smoothblocks.SmoothBlocksGeneratedSide;
import net.minecraft.client.resources.model.cuboid.ItemModelGenerator;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemModelGenerator.class)
public abstract class ItemModelGeneratorMixin {
    @WrapOperation(method = "bakeSideFaces", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/model/geometry/QuadCollection$Builder;addUnculledFace(Lnet/minecraft/client/resources/model/geometry/BakedQuad;)Lnet/minecraft/client/resources/model/geometry/QuadCollection$Builder;"))
    private static QuadCollection.Builder smoothblocks$markSide(QuadCollection.Builder builder, BakedQuad quad,
                                                               Operation<QuadCollection.Builder> original) {
        ((SmoothBlocksGeneratedSide) (Object) quad).smoothblocks$markGeneratedSide();
        return original.call(builder, quad);
    }
}
