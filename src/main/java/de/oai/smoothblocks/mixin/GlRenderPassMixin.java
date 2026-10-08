package de.oai.smoothblocks.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
import de.oai.smoothblocks.SmoothBlocksDrawState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass")
public abstract class GlRenderPassMixin implements SmoothBlocksDrawState {
    @Unique private RenderPipeline smoothblocks$pipeline;
    @Unique private GpuTexture smoothblocks$diffuse;

    @Inject(method = "setPipeline", at = @At("HEAD"))
    private void smoothblocks$pipeline(RenderPipeline pipeline, CallbackInfo ci) {
        this.smoothblocks$pipeline = pipeline;
    }

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void smoothblocks$texture(String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
        if ("Sampler0".equals(name)) smoothblocks$diffuse = view == null ? null : view.texture();
    }

    @Override public RenderPipeline smoothblocks$pipeline() { return smoothblocks$pipeline; }
    @Override public GpuTexture smoothblocks$diffuse() { return smoothblocks$diffuse; }
}
