package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksRenderScope;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Includes GUI item-atlas preparation and entity previews, not just final GUI blits. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void smoothblocks$excludeGui(CallbackInfo ci) { SmoothBlocksRenderScope.endWorld(); }
}
