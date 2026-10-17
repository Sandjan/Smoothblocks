package de.oai.smoothblocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

/** Configures the common reconstruction kernel for the actual world/GUI draw. */
public final class SmoothBlocksEntityXbrzGpuBridge {
    private SmoothBlocksEntityXbrzGpuBridge() {}
    public static final String MODE_UNIFORM = "smoothblocks_EntityMode";
    private record Uniforms(int mode, int metadata, int debug, int[] samplers) {}
    private static final Map<Integer, Uniforms> PROGRAMS = new ConcurrentHashMap<>();
    private static final SmoothBlocksMetadataBinding BINDING = new SmoothBlocksMetadataBinding();

    // Shader compilation can invalidate locations without touching render-thread GL state.
    public static void clearProgramCache() { PROGRAMS.clear(); }
    public static void release() { BINDING.release(); }

    /** Uses this draw's source and world scope; a valid integer metadata sampler is required even in GUI mode. */
    public static void configureActiveEntityProgram(SmoothBlocksDrawState pass) {
        release();
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) return;
        Uniforms u = PROGRAMS.computeIfAbsent(program, SmoothBlocksEntityXbrzGpuBridge::inspect);
        if (u.mode < 0) return;
        boolean world = SmoothBlocksRenderScope.isWorld()
                && SmoothBlocksClient.isWorldEntityPipeline(pass.smoothblocks$pipeline());
        var source = pass.smoothblocks$diffuse();
        int metadata = world ? SmoothBlocksTextureMetadata.textureId(source) : 0;
        int mode = world && metadata != 0 ? SmoothBlocksClient.SHADER_MODE_XBRZ : SmoothBlocksClient.SHADER_MODE_NEAREST;
        // GLSL still validates sampler types when mode=0. Never alias the integer
        // metadata sampler with Sampler0, including GUI draws using the same program.
        int unit = BINDING.bind(program, u.samplers,
                metadata != 0 ? metadata : SmoothBlocksTextureMetadata.fallbackTexture());
        if (unit < 0) {
            throw new IllegalStateException("SmoothBlocks: no unused texture unit for entity metadata");
        }
        GL20C.glUniform1i(u.metadata, unit);
        GL20C.glUniform1i(u.mode, mode);
        if (u.debug >= 0) GL20C.glUniform1i(u.debug, 0);
    }

    private static Uniforms inspect(int program) {
        int mode = GL20C.glGetUniformLocation(program, MODE_UNIFORM);
        if (mode < 0) return new Uniforms(-1, -1, -1, new int[0]);
        int metadata = GL20C.glGetUniformLocation(program, "smoothblocks_EntityMeta");
        return new Uniforms(mode, metadata,
                GL20C.glGetUniformLocation(program, "smoothblocks_EntityDebug"),
                SmoothBlocksMetadataBinding.findSamplerLocations(program, metadata));
    }
}
