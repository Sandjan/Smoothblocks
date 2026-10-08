package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksRenderScope;
import de.oai.smoothblocks.SmoothBlocksTextureMetadata;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    // Reset even after an interrupted previous frame. renderLevel includes first-person hands.
    @Inject(method = "render", at = @At("HEAD"))
    private void smoothblocks$resetScope(CallbackInfo ci) { SmoothBlocksRenderScope.endWorld(); }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void smoothblocks$beginWorld(CallbackInfo ci) {
        SmoothBlocksTextureMetadata.refreshChanged();
        SmoothBlocksRenderScope.beginWorld();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void smoothblocks$endWorld(CallbackInfo ci) { SmoothBlocksRenderScope.endWorld(); }
}
