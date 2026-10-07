package de.oai.smoothblocks;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Writes the runtime mode uniform for entity shaders patched with dynamic xBRZ. */
public final class SmoothBlocksEntityXbrzGpuBridge {
    private SmoothBlocksEntityXbrzGpuBridge() {}

    public static final String MODE_UNIFORM = "smoothblocks_EntityMode";
    private static final Map<Integer, Integer> MODE_LOCATIONS = new ConcurrentHashMap<>();

    public static void clearProgramCache() {
        MODE_LOCATIONS.clear();
    }

    public static void configureActiveEntityProgram() {
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) return;

        int location = MODE_LOCATIONS.computeIfAbsent(program,
                id -> GL20C.glGetUniformLocation(id, MODE_UNIFORM));
        if (location < 0) return;

        int mode = SmoothBlocksClient.getShaderModeCode();
        GL20C.glUniform1i(location, mode);
        SmoothBlocksClient.noteEntityXbrzProgramSetup(mode);
    }
}
