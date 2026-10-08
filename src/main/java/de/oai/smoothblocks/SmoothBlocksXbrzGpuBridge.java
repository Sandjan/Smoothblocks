package de.oai.smoothblocks;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Binds the compact edge-map texture to whichever Sodium/Iris terrain program is currently active. */
public final class SmoothBlocksXbrzGpuBridge {
    private SmoothBlocksXbrzGpuBridge() {}

    public static final String MODE_UNIFORM = "smoothblocks_Mode";
    public static final String META_UNIFORM = "smoothblocks_Meta";
    public static final String DEBUG_UNIFORM = "smoothblocks_Debug";

    private record ProgramUniforms(int modeLocation, int metaLocation, int debugLocation,
                                   int[] samplerLocations, int diffuseSamplerLocation) {}
    private static final Map<Integer, ProgramUniforms> UNIFORMS = new ConcurrentHashMap<>();

    // Keep the custom binding scoped to one Sodium terrain pass.
    private static final SmoothBlocksMetadataBinding BINDING = new SmoothBlocksMetadataBinding();
    private static volatile String sourceTextureStatus = "?";


    public static void clearProgramCache() {
        releaseMetadataTexture();
        UNIFORMS.clear();
    }

    /** Called after Sodium/Iris has bound and configured the active chunk shader. */
    public static void configureActiveTerrainProgram() {
        releaseMetadataTexture();

        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) {
            SmoothBlocksClient.noteXbrzProgramBind(SmoothBlocksClient.getShaderModeCode(), -1, -1, "no-current-program");
            return;
        }

        ProgramUniforms u = UNIFORMS.computeIfAbsent(program, SmoothBlocksXbrzGpuBridge::inspectProgram);
        sourceTextureStatus = checkSourceTexture(program, u.diffuseSamplerLocation);
        if (u.modeLocation < 0) {
            SmoothBlocksClient.noteXbrzProgramBind(SmoothBlocksClient.getShaderModeCode(), -1, -1, "shader-not-patched");
            return;
        }

        int requestedMode = SmoothBlocksClient.getShaderModeCode();
        int effectiveMode = requestedMode;
        int unit = -1;
        String status = "not-xbrz";

        if (requestedMode == SmoothBlocksClient.SHADER_MODE_XBRZ) {
            if (u.metaLocation < 0) {
                effectiveMode = SmoothBlocksClient.SHADER_MODE_NEAREST;
                status = "meta-uniform-missing";
            } else if (!SmoothBlocksXbrzMetadata.ready()) {
                effectiveMode = SmoothBlocksClient.SHADER_MODE_NEAREST;
                status = "metadata-not-ready";
            } else {
                unit = bindMetadataTexture(program, u.samplerLocations);
                if (unit < 0) {
                    effectiveMode = SmoothBlocksClient.SHADER_MODE_NEAREST;
                    status = "no-free-texture-unit";
                } else {
                    GL20C.glUniform1i(u.metaLocation, unit);

                    status = "active-unit-" + unit;
                }
            }
        }

        GL20C.glUniform1i(u.modeLocation, effectiveMode);
        if (u.debugLocation >= 0) {
            GL20C.glUniform1i(u.debugLocation, SmoothBlocksClient.getDebugViewCode());
        }
        SmoothBlocksClient.noteXbrzProgramBind(requestedMode, effectiveMode, unit, status);
    }

    private static ProgramUniforms inspectProgram(int program) {
        int mode = GL20C.glGetUniformLocation(program, MODE_UNIFORM);
        int meta = GL20C.glGetUniformLocation(program, META_UNIFORM);
        int debug = GL20C.glGetUniformLocation(program, DEBUG_UNIFORM);
        int diffuse = findDiffuseSamplerLocation(program);
        if (mode < 0) return new ProgramUniforms(mode, meta, debug, new int[0], diffuse);
        return new ProgramUniforms(mode, meta, debug, SmoothBlocksMetadataBinding.findSamplerLocations(program, meta), diffuse);
    }

    private static int findDiffuseSamplerLocation(int program) {
        for (String name : List.of("gtexture", "u_BlockTex", "Sampler0", "tex", "texture", "albedo", "diffuse", "diffuseTex")) {
            int location = GL20C.glGetUniformLocation(program, name);
            if (location >= 0) return location;
        }
        return -1;
    }

    private static String checkSourceTexture(int program, int samplerLocation) {
        if (samplerLocation < 0) return "?";
        int expected = SmoothBlocksXbrzMetadata.sourceTextureId();
        if (expected == 0) return "?";
        int unit = GL20C.glGetUniformi(program, samplerLocation);
        if (unit < 0) return "bad-unit";
        int max = GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        if (unit >= max) return "bad-unit";

        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        try {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            int actual = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            return actual == expected ? "ok" : "diff";
        } finally {
            GL13C.glActiveTexture(previousActive);
        }
    }

    public static String bindingStatsString() {
        return "src=" + sourceTextureStatus + " dr=" + BINDING.drifts();
    }

    public static void resetDiagnostics() {
        sourceTextureStatus = "?";
        BINDING.resetDiagnostics();
    }

    public static void releaseMetadataTexture() { BINDING.release(); }
    private static int bindMetadataTexture(int program, int[] locations) {
        return BINDING.bind(program, locations, SmoothBlocksXbrzMetadata.textureId());
    }
}
