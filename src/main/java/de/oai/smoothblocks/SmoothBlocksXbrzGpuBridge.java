package de.oai.smoothblocks;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL33C;

import java.nio.IntBuffer;
import java.util.ArrayList;
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

    // The custom binding is deliberately scoped to one Sodium terrain pass.  These are
    // the exact raw GL values we must restore afterwards; Minecraft never needs to know
    // that the temporary high-numbered texture unit existed.
    private static volatile int activeMetadataUnit = -1;
    private static volatile int previousTexture2d = 0;
    private static volatile int previousSampler = 0;
    private static volatile String sourceTextureStatus = "?";
    private static volatile long metadataBindingDrifts = 0;

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
                    activeMetadataUnit = unit;
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
        return new ProgramUniforms(mode, meta, debug, findSamplerLocations(program, meta), diffuse);
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
        return "src=" + sourceTextureStatus + " dr=" + metadataBindingDrifts;
    }

    public static void resetDiagnostics() {
        sourceTextureStatus = "?";
        metadataBindingDrifts = 0;
    }

    /**
     * Cache the locations of all sampler uniforms used by this linked program.  Their
     * assigned unit values are read at draw time, because Iris is free to reassign them.
     */
    private static int[] findSamplerLocations(int program, int metaLocation) {
        int count = GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORMS);
        int maxName = Math.max(64, GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORM_MAX_LENGTH));
        IntBuffer size = BufferUtils.createIntBuffer(1);
        IntBuffer type = BufferUtils.createIntBuffer(1);
        List<Integer> locations = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            size.clear();
            type.clear();
            String name = GL20C.glGetActiveUniform(program, i, maxName, size, type);
            if (name == null || name.isEmpty() || !isSamplerType(type.get(0))) continue;

            int arraySize = Math.max(1, size.get(0));
            String base = name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name;
            for (int element = 0; element < arraySize; element++) {
                String elementName = arraySize == 1 ? name : base + "[" + element + "]";
                int location = GL20C.glGetUniformLocation(program, elementName);
                if (location >= 0 && location != metaLocation) locations.add(location);
            }
        }

        int[] out = new int[locations.size()];
        for (int i = 0; i < out.length; i++) out[i] = locations.get(i);
        return out;
    }

    private static boolean isSamplerType(int type) {
        // Core sampler enums from GL 2.0 through GL 4.x. Numeric constants keep this
        // helper independent of which optional GL class introduced each sampler kind.
        return switch (type) {
            case 0x8B5D, 0x8B5E, 0x8B5F, 0x8B60, 0x8B61, 0x8B62, 0x8B63, 0x8B64,
                 0x8DC0, 0x8DC1, 0x8DC2, 0x8DC3, 0x8DC4, 0x8DC5,
                 0x8DC9, 0x8DCA, 0x8DCB, 0x8DCC, 0x8DCD, 0x8DCE, 0x8DCF, 0x8DD0,
                 0x8DD1, 0x8DD2, 0x8DD3, 0x8DD4, 0x8DD5, 0x8DD6, 0x8DD7, 0x8DD8,
                 0x900C, 0x900D, 0x900E, 0x900F,
                 0x9108, 0x9109, 0x910A, 0x910B, 0x910C, 0x910D -> true;
            default -> false;
        };
    }

    /** Release our temporary terrain metadata binding after the chunk draw pass. */
    public static void releaseMetadataTexture() {
        int unit = activeMetadataUnit;
        if (unit < 0) return;

        int restoreTexture = previousTexture2d;
        int restoreSampler = previousSampler;
        activeMetadataUnit = -1;
        previousTexture2d = 0;
        previousSampler = 0;

        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        try {
            // Raw GL is intentional. Units >= 12 are outside Minecraft 26.1.2's
            // GlStateManager cache and must never be passed to _activeTexture().
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            int stillBound = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            if (stillBound != SmoothBlocksXbrzMetadata.textureId()) metadataBindingDrifts++;
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, restoreTexture);
            GL33C.glBindSampler(unit, restoreSampler);
        } finally {
            GL13C.glActiveTexture(previousActive);
        }
    }

    /**
     * Bind metadata on a texture unit that the current terrain shader does not use.
     *
     * <p>v1.5.5 only searched Minecraft's cached units 3..11 and required them to be
     * globally empty. Iris leaves perfectly harmless bindings on those units, so that
     * test produced an apparent mixed-path rate in earlier diagnostics. Here we instead inspect
     * the sampler units actually referenced by the active program and prefer a raw GL
     * unit >= 12. This is safe because the raw binding is fully restored after the pass
     * and never enters GlStateManager's 12-slot cache.</p>
     */
    private static int bindMetadataTexture(int program, int[] samplerLocations) {
        int metaTexture = SmoothBlocksXbrzMetadata.textureId();
        if (metaTexture == 0) return -1;

        int combinedMax = Math.max(0, GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
        int fragmentMax = Math.max(0, GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS));
        int searchMax = Math.min(combinedMax, fragmentMax) - 1;
        if (searchMax < 3) return -1;

        boolean[] used = new boolean[searchMax + 1];
        for (int location : samplerLocations) {
            int unit = GL20C.glGetUniformi(program, location);
            if (unit >= 0 && unit < used.length) used[unit] = true;
        }

        int chosen = -1;
        // Prefer a unit that Minecraft's GlStateManager cannot represent. This avoids
        // both the old "Texture N out of range" crash and any cache desynchronisation.
        for (int unit = searchMax; unit >= 12; unit--) {
            if (!used[unit]) {
                chosen = unit;
                break;
            }
        }
        // Very old hardware fallback: use an actually-unused cached unit.
        if (chosen < 0) {
            for (int unit = Math.min(11, searchMax); unit >= 3; unit--) {
                if (!used[unit]) {
                    chosen = unit;
                    break;
                }
            }
        }
        if (chosen < 0) return -1;

        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        try {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + chosen);
            previousTexture2d = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            previousSampler = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);

            // Integer RG8UI metadata must use nearest sampling. A sampler object bound
            // by Iris overrides the texture's own filter state, so temporarily unbind it.
            GL33C.glBindSampler(chosen, 0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, metaTexture);
        } finally {
            GL13C.glActiveTexture(previousActive);
        }
        return chosen;
    }
}
