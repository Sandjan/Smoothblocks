package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksEntityXbrzGpuBridge;
import de.oai.smoothblocks.SmoothBlocksDrawState;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Collection;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import de.oai.smoothblocks.SmoothBlocksTextureMetadata;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

/** Configures resource-time entity metadata after the final Minecraft/Iris program setup. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIIIIIII)V", at = @At("RETURN"))
    private void smoothblocks$changedImage(GpuTexture texture, NativeImage image, int mip, int layer,
            int x, int y, int width, int height, int sourceX, int sourceY, CallbackInfo ci) {
        if (mip == 0) SmoothBlocksTextureMetadata.changed(texture);
    }

    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;Lcom/mojang/blaze3d/platform/NativeImage$Format;IIIIII)V", at = @At("RETURN"))
    private void smoothblocks$changedBuffer(GpuTexture texture, ByteBuffer pixels, NativeImage.Format format,
            int mip, int layer, int x, int y, int width, int height, CallbackInfo ci) {
        if (mip == 0) SmoothBlocksTextureMetadata.changed(texture);
    }

    @Inject(method = "copyTextureToTexture", at = @At("RETURN"))
    private void smoothblocks$changedCopy(GpuTexture source, GpuTexture destination, int mip,
            int sourceX, int sourceY, int destX, int destY, int width, int height, CallbackInfo ci) {
        if (mip == 0) SmoothBlocksTextureMetadata.changed(destination);
    }

    // Wrap the CALL, not trySetup's RETURN: Iris also injects at RETURN and may
    // cancel at HEAD. This runs after its final program/sampler setup in both cases.
    @WrapOperation(method = {"executeDraw", "executeDrawMultiple"}, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;trySetup(Lcom/mojang/blaze3d/opengl/GlRenderPass;Ljava/util/Collection;)Z"))
    private boolean smoothblocks$configureEntityMode(@Coerce Object encoder, @Coerce Object pass,
                                                     Collection<String> uniforms, Operation<Boolean> original) {
        boolean ready = original.call(encoder, pass, uniforms);
        if (ready) {
            SmoothBlocksEntityXbrzGpuBridge.configureActiveEntityProgram((SmoothBlocksDrawState) pass);
        }
        return ready;
    }

    @Inject(method = {"executeDraw", "executeDrawMultiple"}, at = @At("RETURN"))
    private void smoothblocks$restoreMetadataBinding(CallbackInfo ci) {
        SmoothBlocksEntityXbrzGpuBridge.release();
    }
}
