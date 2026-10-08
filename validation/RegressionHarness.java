package de.oai.smoothblocks;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Checks actual 26.1.2 resources and hook descriptors without starting Minecraft. */
public final class RegressionHarness {
    public static void main(String[] args) throws Exception {
        for (String name : new String[]{"entity", "item"}) {
            String original = new String(resource("assets/minecraft/shaders/core/" + name + ".fsh"), StandardCharsets.UTF_8);
            String patched = SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect("minecraft:core/" + name, original);
            require(!patched.equals(original), "Original " + name + " shader must be patched");
            require(patched.equals(SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect("minecraft:core/" + name, patched)), "Patching must be idempotent");
            require(patched.contains("uniform usampler2D smoothblocks_EntityMeta"), "Precomputed metadata must be used");
            require(!patched.contains("smoothblocks_EStates("), "No fragment-time classifier");
            require(patched.contains("smoothblocks_EntityFetch"), "Shared bounded reconstruction");
            require(!patched.contains("color.a = center.a"), "Item alpha must use the shared reconstruction");
        }
        for (String name : new String[]{"gui", "gui_item", "rendertype_text", "particle", "position_tex"}) {
            String source = "#version 330\nuniform sampler2D Sampler0; void main(){vec4 c=texture(Sampler0,vec2(0));}";
            require(source.equals(SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect("minecraft:core/" + name, source)), "Excluded shader " + name);
        }
        SmoothBlocksRenderScope.endWorld();
        require(!SmoothBlocksRenderScope.isWorld(), "Default GUI/off");
        SmoothBlocksRenderScope.beginWorld();
        require(SmoothBlocksRenderScope.isWorld(), "World enabled");
        SmoothBlocksRenderScope.endWorld();
        require(!SmoothBlocksRenderScope.isWorld(), "GUI after world disabled");

        // Target names/signatures are verified against the supported Minecraft binary.
        method("net/minecraft/client/renderer/ShaderManager$CompilationCache", "getShaderSource",
                "(Lnet/minecraft/resources/Identifier;Lcom/mojang/blaze3d/shaders/ShaderType;)Ljava/lang/String;");
        method("com/mojang/blaze3d/opengl/GlCommandEncoder", "trySetup",
                "(Lcom/mojang/blaze3d/opengl/GlRenderPass;Ljava/util/Collection;)Z");
        method("com/mojang/blaze3d/opengl/GlCommandEncoder", "writeToTexture",
                "(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIIIIIII)V");
        method("com/mojang/blaze3d/opengl/GlCommandEncoder", "writeToTexture",
                "(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;Lcom/mojang/blaze3d/platform/NativeImage$Format;IIIIII)V");
        method("com/mojang/blaze3d/opengl/GlCommandEncoder", "copyTextureToTexture",
                "(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/textures/GpuTexture;IIIIIII)V");
        method("net/minecraft/client/renderer/GameRenderer", "renderLevel", "(Lnet/minecraft/client/DeltaTracker;)V");
        method("net/minecraft/client/gui/render/GuiRenderer", "render", "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V");
        alphaInvariance();
        System.out.println("PASS integration: original entity/item shaders, GUI scope, hook descriptors, transparent RGB invariance");
    }

    static byte[] resource(String path) throws Exception {
        try (var stream = RegressionHarness.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new AssertionError("Missing resource " + path);
            return stream.readAllBytes();
        }
    }

    private static void method(String owner, String name, String descriptor) throws Exception {
        ClassNode cls = new ClassNode(Opcodes.ASM9);
        new ClassReader(resource(owner + ".class")).accept(cls, ClassReader.SKIP_CODE);
        require(cls.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)), "Missing hook " + owner + "." + name + descriptor);
    }

    private static void alphaInvariance() {
        int[] a = new int[64], b = new int[64];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            a[y * 8 + x] = x > y ? 0xFFFFFFFF : 0;
            b[y * 8 + x] = x > y ? 0xFFFFFFFF : (x * 71231 + y * 17779) & 0xFFFFFF;
        }
        require(Arrays.equals(SmoothBlocksXbrzCore.classifyArgb(8, 8, a).metadata(),
                SmoothBlocksXbrzCore.classifyArgb(8, 8, b).metadata()), "Transparent RGB must not change metadata");
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
