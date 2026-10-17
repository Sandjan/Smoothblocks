package de.oai.smoothblocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

/** Binds metadata after Sodium/Iris has finished configuring its terrain program. */
public final class SmoothBlocksXbrzGpuBridge {
    public static final String MODE_UNIFORM = "smoothblocks_Mode";
    public static final String META_UNIFORM = "smoothblocks_Meta";
    public static final String DEBUG_UNIFORM = "smoothblocks_Debug";

    private record Uniforms(int mode, int metadata, int debug, int[] samplers) {}
    private static final Map<Integer, Uniforms> PROGRAMS = new ConcurrentHashMap<>();
    private static final SmoothBlocksMetadataBinding BINDING = new SmoothBlocksMetadataBinding();

    private SmoothBlocksXbrzGpuBridge() {}

    /** Relinking can reuse program IDs, so cached uniform locations must expire explicitly. */
    // Shader compilation can invalidate locations without touching render-thread GL state.
    public static void clearProgramCache() {
        PROGRAMS.clear();
    }

    public static void configureActiveTerrainProgram() {
        releaseMetadataTexture();
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) return;
        Uniforms uniforms = PROGRAMS.computeIfAbsent(program, SmoothBlocksXbrzGpuBridge::inspect);
        if (uniforms.mode < 0) return;

        int mode = SmoothBlocksClient.SHADER_MODE_NEAREST;
        if (uniforms.metadata >= 0) {
            int metadata = SmoothBlocksXbrzMetadata.textureId();
            // A valid integer sampler is required even when reconstruction falls back to nearest.
            int unit = BINDING.bind(program, uniforms.samplers,
                    metadata != 0 ? metadata : SmoothBlocksTextureMetadata.fallbackTexture());
            if (unit < 0) throw new IllegalStateException("SmoothBlocks: no unused terrain metadata texture unit");
            GL20C.glUniform1i(uniforms.metadata, unit);
            if (metadata != 0) mode = SmoothBlocksClient.SHADER_MODE_XBRZ;
        }
        GL20C.glUniform1i(uniforms.mode, mode);
        if (uniforms.debug >= 0) GL20C.glUniform1i(uniforms.debug, 0);
    }

    private static Uniforms inspect(int program) {
        int mode = GL20C.glGetUniformLocation(program, MODE_UNIFORM);
        int metadata = GL20C.glGetUniformLocation(program, META_UNIFORM);
        int debug = GL20C.glGetUniformLocation(program, DEBUG_UNIFORM);
        return new Uniforms(mode, metadata, debug, mode < 0 ? new int[0]
                : SmoothBlocksMetadataBinding.findSamplerLocations(program, metadata));
    }

    public static void releaseMetadataTexture() { BINDING.release(); }
}
