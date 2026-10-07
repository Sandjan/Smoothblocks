package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksEntityXbrzGpuBridge;
import de.oai.smoothblocks.SmoothBlocksXbrzGpuBridge;
import de.oai.smoothblocks.SmoothBlocksXbrzMetadata;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Builds the compact xBRZ edge metadata alongside Minecraft's block atlas. */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Inject(method = "upload", at = @At("TAIL"))
    private void smoothblocks$buildXbrzMetadata(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        TextureAtlas self = (TextureAtlas) (Object) this;
        if (!TextureAtlas.LOCATION_BLOCKS.equals(self.location())) {
            return;
        }

        SmoothBlocksXbrzMetadata.rebuild(self, preparations);
        SmoothBlocksXbrzGpuBridge.clearProgramCache();
        SmoothBlocksEntityXbrzGpuBridge.clearProgramCache();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void smoothblocks$closeXbrzMetadata(CallbackInfo ci) {
        TextureAtlas self = (TextureAtlas) (Object) this;
        if (TextureAtlas.LOCATION_BLOCKS.equals(self.location())) {
            SmoothBlocksXbrzMetadata.close();
            SmoothBlocksXbrzGpuBridge.clearProgramCache();
            SmoothBlocksEntityXbrzGpuBridge.clearProgramCache();
        }
    }
}
