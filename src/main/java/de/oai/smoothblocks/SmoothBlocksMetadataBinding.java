package de.oai.smoothblocks;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL33C;

/** Shared scoped metadata binding for terrain passes and entity draws. */
final class SmoothBlocksMetadataBinding {
    private int activeMetadataUnit = -1;
    private int previousTexture2d;
    private int previousSampler;
    private boolean[] used;
    /** Includes every active sampler-array element except metadata, so unit selection also protects auxiliary maps. */
    static int[] findSamplerLocations(int program, int metaLocation) {
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

    /** Restores the saved texture and sampler on the borrowed unit; repeated release is harmless. */
    public void release() {
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
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, restoreTexture);
            GL33C.glBindSampler(unit, restoreSampler);
        } finally {
            GL13C.glActiveTexture(previousActive);
        }
    }

    /** Borrows a unit unused by active shader samplers; returns -1 if unavailable. Caller must release after drawing. */
    public int bind(int program, int[] samplerLocations, int metaTexture) {
        release();
        if (metaTexture == 0) return -1;

        if (used == null) {
            int combinedMax = Math.max(0, GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
            int fragmentMax = Math.max(0, GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS));
            used = new boolean[Math.min(combinedMax, fragmentMax)];
        }
        int searchMax = used.length - 1;
        if (searchMax < 3) return -1;

        Arrays.fill(used, false);
        for (int location : samplerLocations) {
            int unit = GL20C.glGetUniformi(program, location);
            if (unit >= 0 && unit < used.length) used[unit] = true;
        }

        int chosen = -1;
        // Descending order prefers units outside Minecraft's 12-slot cache, then unused cached units.
        for (int unit = searchMax; unit >= 3; unit--) {
            if (used[unit]) continue;
            chosen = unit;
            break;
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
        activeMetadataUnit = chosen;
        return chosen;
    }
}
