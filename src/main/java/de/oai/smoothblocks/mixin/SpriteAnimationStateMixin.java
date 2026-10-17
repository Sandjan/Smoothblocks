package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksAnimatedMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Select matching precomputed metadata only after Minecraft has drawn the current frame into the atlas. */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public abstract class SpriteAnimationStateMixin {
    @Shadow private int frame;
    @Shadow private int subFrame;
    @Inject(method = "drawToAtlas", at = @At("RETURN"))
    private void smoothblocks$selectFrame(CallbackInfo ci) {
        SmoothBlocksAnimatedMetadata.update(this, frame, subFrame);
    }
}
