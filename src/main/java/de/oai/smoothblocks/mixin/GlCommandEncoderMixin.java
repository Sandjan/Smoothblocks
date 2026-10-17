package de.oai.smoothblocks.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.oai.smoothblocks.SmoothBlocksDrawState;
import de.oai.smoothblocks.SmoothBlocksEntityXbrzGpuBridge;
import de.oai.smoothblocks.SmoothBlocksTextureMetadata;
import java.nio.ByteBuffer;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks level-0 writes and configures metadata after final program setup; higher mips do not affect classification. */
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

    // Wrap the caller: Iris can cancel trySetup at HEAD or alter setup at RETURN; both must finish first.
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

    /** Cover the entire draw, including exceptions after Iris or metadata setup. */
    @WrapMethod(method = "executeDraw")
    private void smoothblocks$scopeDraw(@Coerce Object pass, int baseVertex, int firstIndex, int count,
                                        VertexFormat.IndexType indexType, int instances, Operation<Void> original) {
        try {
            original.call(pass, baseVertex, firstIndex, count, indexType, instances);
        } finally {
            SmoothBlocksEntityXbrzGpuBridge.release();
        }
    }

    @WrapMethod(method = "executeDrawMultiple")
    private void smoothblocks$scopeMultiple(@Coerce Object pass, Collection<?> draws, GpuBuffer indexBuffer,
                                            VertexFormat.IndexType indexType, Collection<String> uniforms,
                                            Object userData, Operation<Void> original) {
        try {
            original.call(pass, draws, indexBuffer, indexType, uniforms, userData);
        } finally {
            SmoothBlocksEntityXbrzGpuBridge.release();
        }
    }
}
