package de.oai.smoothblocks.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import de.oai.smoothblocks.SmoothBlocksTextureMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlTexture.class)
public abstract class GlTextureMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void smoothblocks$release(CallbackInfo ci) {
        SmoothBlocksTextureMetadata.forget((GlTexture) (Object) this);
    }
}
