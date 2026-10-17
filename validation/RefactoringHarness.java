package de.oai.smoothblocks;

import java.util.Arrays;
import java.util.List;

import static de.oai.smoothblocks.RegressionHarness.require;

/** Boundary and policy checks stay independent of GL, shader helper names and implementation spelling. */
public final class RefactoringHarness {
    public static void main(String[] args) {
        invalidDimensions();
        atlasIsolation();
        shaderComments();
        System.out.println("PASS refactoring: overflow rejection, atlas isolation, animation sentinels, comment-safe shader rewriting");
    }

    private static void invalidDimensions() {
        rejects(() -> SmoothBlocksXbrzCore.classifyArgb(65536, 65536, new int[0]));
        rejects(() -> SmoothBlocksXbrzCore.classifyArgb(0, 1, new int[0]));
        rejects(() -> SmoothBlocksXbrzCore.classifyArgb(2, 2, new int[3]));
    }

    private static void atlasIsolation() {
        int[] pixels = new int[24];
        Arrays.fill(pixels, 0xFF00FF00);
        List<SmoothBlocksTextureMetadata.Region> regions = List.of(
                new SmoothBlocksTextureMetadata.Region(0, 0, 2, 2, false),
                new SmoothBlocksTextureMetadata.Region(2, 0, 2, 2, false),
                new SmoothBlocksTextureMetadata.Region(4, 0, 1, 2, true));
        for (int y = 0; y < 2; y++) Arrays.fill(pixels, y * 6, y * 6 + 2, 0xFFFF0000);
        short[] result = SmoothBlocksTextureMetadata.classify(6, 4, pixels, regions);
        short[] isolated = SmoothBlocksXbrzCore.classifyArgb(2, 2,
                new int[]{0xFFFF0000, 0xFFFF0000, 0xFFFF0000, 0xFFFF0000}).metadata();
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) require(result[y * 6 + x] == isolated[y * 2 + x], "Neighbor must not alter sprite");
            require((result[y * 6 + 4] & 0xFFFF) == 0xFFFF, "Animated region sentinel");
            require((result[y * 6 + 5] & 0xFFFF) == 0xFFFE, "Horizontal padding sentinel");
        }
        for (int i = 12; i < result.length; i++) require((result[i] & 0xFFFF) == 0xFFFE, "Vertical padding sentinel");
        rejects(() -> SmoothBlocksTextureMetadata.classify(6, 4, pixels,
                List.of(new SmoothBlocksTextureMetadata.Region(Integer.MAX_VALUE, 0, 2, 2, false))));
    }

    private static void shaderComments() {
        String source = """
                #version 330 core
                /* A header comment
                   with { } and a fake void main() */
                #extension GL_ARB_shader_texture_lod : enable
                /* Preserve this
                   header */ uniform sampler2D Sampler0;
                // uniform sampler2D gtexture;
                // void main() { texture(gtexture, vec2(0)); }
                vec4 probe(vec2 uv) { return textureLod(Sampler0, uv, 0.0); }
                void main() {
                    // } texture(Sampler0, vec2(0));
                    /* nested-looking { texture(Sampler0, vec2(0)); */
                    vec4 color = texture(Sampler0, vec2(.5));
                }
                """;
        String terrain = SmoothBlocksShaderPatch.patchIrisTerrainFragmentPostTransform(source);
        require(terrain.contains("smoothblocks_TerrainSample(Sampler0, vec2(.5))"), "Comment braces must not truncate main");
        require(terrain.contains("// } texture(Sampler0, vec2(0));"), "Commented calls must remain untouched");
        require(terrain.contains("return textureLod(Sampler0, uv, 0.0)"), "Helper probes must remain untouched");
        require(terrain.indexOf("#extension") < terrain.indexOf("uniform usampler2D"), "Extensions precede helpers");
        require(terrain.indexOf("header */") < terrain.indexOf("uniform usampler2D"), "Helpers must not split header comments");
        require(terrain.equals(SmoothBlocksShaderPatch.patchIrisTerrainFragmentPostTransform(terrain)), "Terrain patch idempotency");
        String entity = SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect("entity", source);
        require(entity.contains("smoothblocks_EntitySample(Sampler0, vec2(.5))"), "Shared sampler selection");
        require(entity.contains("// } texture(Sampler0, vec2(0));"), "Entity comments stay unchanged");
        require(entity.equals(SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect("entity", entity)), "Entity patch idempotency");
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Invalid inputs must be rejected before indexing or allocating image data.
        }
    }
}
