package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksGeneratedSide;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries bake-time wall provenance on each quad; draw-time omission does not mutate shared model geometry. */
@Mixin(BakedQuad.class)
public abstract class BakedQuadMixin implements SmoothBlocksGeneratedSide {
    @Unique private boolean smoothblocks$generatedSide;
    @Override public void smoothblocks$markGeneratedSide() { smoothblocks$generatedSide = true; }
    @Override public boolean smoothblocks$isGeneratedSide() { return smoothblocks$generatedSide; }
}
