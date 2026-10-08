package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksGeneratedSide;
import de.oai.smoothblocks.SmoothBlocksSideCollector;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(QuadCollection.Builder.class)
public abstract class QuadCollectionBuilderMixin implements SmoothBlocksSideCollector {
    @Unique private boolean smoothblocks$collectingSides;

    @Override public void smoothblocks$collectSides(boolean enabled) {
        smoothblocks$collectingSides = enabled;
    }

    @Inject(method = "addUnculledFace", at = @At("HEAD"))
    private void smoothblocks$markSide(BakedQuad quad, CallbackInfoReturnable<QuadCollection.Builder> cir) {
        if (smoothblocks$collectingSides)
            ((SmoothBlocksGeneratedSide) (Object) quad).smoothblocks$markGeneratedSide();
    }
}
