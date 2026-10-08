package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksTextureMetadata;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void smoothblocks$closeMetadata(CallbackInfo ci) {
        SmoothBlocksTextureMetadata.close();
        de.oai.smoothblocks.SmoothBlocksClient.closeCaches();
    }

    @Inject(method = "register", at = @At("TAIL"))
    private void smoothblocks$prepareDynamic(Identifier id, AbstractTexture texture, CallbackInfo ci) {
        // Downloaded player skins/capes are DynamicTextures and bypass ReloadableTexture.apply.
        if (texture instanceof DynamicTexture) SmoothBlocksTextureMetadata.registerTexture(texture.getTexture());
    }
}
