package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksAlphaEdgeRepair;
import de.oai.smoothblocks.SmoothBlocksClient;
import de.oai.smoothblocks.SmoothBlocksTextureMetadata;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Repairs hidden RGB on fully transparent edge pixels before an individual
 * entity/armor texture is uploaded. Hardware linear filtering then has a
 * sensible edge color instead of interpolating transparent black.
 */
@Mixin(ReloadableTexture.class)
public abstract class ReloadableTextureMixin {
    @Inject(method = "apply", at = @At("TAIL"))
    private void smoothblocks$prepareMetadata(TextureContents contents, CallbackInfo ci) {
        String path = resourceId().getPath();
        if (path.startsWith("textures/entity/") || path.startsWith("textures/models/armor/")
                || path.startsWith("textures/equipment/")) {
            var texture = ((ReloadableTexture) (Object) this).getTexture();
            SmoothBlocksTextureMetadata.forget(texture);
            SmoothBlocksTextureMetadata.registerTexture(texture);
        }
    }

    @Shadow
    public abstract net.minecraft.resources.Identifier resourceId();

    @Inject(method = "apply", at = @At("HEAD"))
    private void smoothblocks$repairEntityAlpha(TextureContents contents, CallbackInfo ci) {
        var id = this.resourceId();
        String path = id.getPath();
        if (!path.startsWith("textures/entity/") && !path.startsWith("textures/models/armor/")) {
            return;
        }

        int repaired = SmoothBlocksAlphaEdgeRepair.repair(contents);
        if (repaired > 0) {
            SmoothBlocksClient.noteEntityAlphaEdgeRepair(repaired, id.toString());
        }
    }
}
