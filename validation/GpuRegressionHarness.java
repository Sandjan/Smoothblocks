package de.oai.smoothblocks;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.AddressMode;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.lwjgl.opengl.GL33C.*;
import static de.oai.smoothblocks.RegressionHarness.require;

/** Driver compilation + offscreen pixel checks; no visible window and no game launch. */
public final class GpuRegressionHarness {
    private static final int SIZE = 64;
    private static final String VERTEX = """
            #version 330 core
            out vec2 uv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            uniform sampler2D Sampler0;
            in vec2 uv;
            out vec4 color;
            void main() { color = texture(Sampler0, uv); }
            """;

    public static void main(String[] args) {
        require(GLFW.glfwInit(), "GLFW init");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "SmoothBlocks offscreen regression", 0, 0);
        require(window != 0, "Hidden GL context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            glBindVertexArray(glGenVertexArrays());
            int framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            int output = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, output);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0);
            require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "Framebuffer");
            glViewport(0, 0, SIZE, SIZE);
            glDisable(GL_DITHER);
            int original = program(FRAGMENT);
            int entity = program(SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect("minecraft:core/entity", FRAGMENT));
            int irisEntity = program(SmoothBlocksShaderPatch.finishIrisEntityFragment(
                    SmoothBlocksShaderPatch.markIrisEntitySamples("gbuffers_entities", FRAGMENT)));
            int terrain = program(SmoothBlocksShaderPatch.patchIrisTerrainFragmentPostTransform(FRAGMENT));
            int[] pixels = new int[64];
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                pixels[y * 8 + x] = x > y ? 0xFFFFAA33 : 0;
            }
            int source = upload(pixels, 8, 8);
            TestTexture texture = new TestTexture(source, 8, 8);
            SmoothBlocksTextureMetadata.registerTexture(texture);
            int meta = SmoothBlocksTextureMetadata.textureId(texture);
            require(meta != 0, "Resource-time metadata built");
            long builds = SmoothBlocksTextureMetadata.buildCount();
            byte[] baseline = draw(original, source, meta, "", 0);
            byte[] gui = draw(entity, source, meta, "Entity", 0);
            require(Arrays.equals(baseline, gui), "GUI/off output must be byte-identical to original");
            byte[] world = draw(entity, source, meta, "Entity", 2);
            require(!Arrays.equals(world, baseline), "World must actually interpolate diagonal");
            require(Arrays.equals(world, draw(irisEntity, source, meta, "Entity", 2)), "Iris and vanilla reconstruction parity");
            require(Arrays.equals(world, draw(terrain, source, meta, "", 2)), "Terrain/entity common p1 parity");
            animatedFrames(entity, source, pixels, world);
            periodicBlock();
            checkDrawPolicy(entity, texture, world, baseline);
            int itemProgram = program(SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect("minecraft:core/item", FRAGMENT));
            byte[] item = draw(itemProgram, source, meta, "Entity", 2);
            require(Arrays.equals(item, world), "Item RGBA must match the shared terrain/entity reconstruction");
            boolean softenedAlpha = false;
            for (int i = 3; i < item.length; i += 4) {
                int alpha = item[i] & 255;
                if (alpha > 0 && alpha < 255 && item[i] != baseline[i]) softenedAlpha = true;
            }
            require(softenedAlpha, "Item silhouette must interpolate alpha");

            for (int i = 0; i < 1000; i++) require(SmoothBlocksTextureMetadata.textureId(texture) == meta, "Stable lookup");
            SmoothBlocksTextureMetadata.refreshChanged();
            require(builds == SmoothBlocksTextureMetadata.buildCount(), "Unchanged frames must not rebuild");
            SmoothBlocksTextureMetadata.changed(texture);
            require(SmoothBlocksTextureMetadata.textureId(texture) == 0, "Dirty metadata must not be sampled");
            SmoothBlocksTextureMetadata.refreshChanged();
            require(builds == SmoothBlocksTextureMetadata.buildCount(), "Identical uploads must not reclassify");
            pixels[0] = 0xFF00FF00;
            writePixels(source, pixels, 8, 8);
            SmoothBlocksTextureMetadata.changed(texture);
            SmoothBlocksTextureMetadata.changed(texture);
            SmoothBlocksTextureMetadata.refreshChanged();
            require(builds + 1 == SmoothBlocksTextureMetadata.buildCount(), "Changed uploads coalesced into one build");
            SmoothBlocksTextureMetadata.forget(texture);
            require(SmoothBlocksTextureMetadata.textureId(texture) == 0, "Disposed metadata must not survive");
            boundaryAndBinding(entity);
            SmoothBlocksTextureMetadata.close();
            require(glGetError() == GL_NO_ERROR, "OpenGL error");
            System.out.println("PASS GPU: " + glGetString(GL_RENDERER)
                    + "; GUI pixel parity, vanilla/Iris/terrain parity, item alpha, sprite boundaries, binding restoration, cache invalidation");
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void checkDrawPolicy(int program, TestTexture texture, byte[] world, byte[] baseline) {
        RenderPipeline pipeline = RenderPipeline.builder().withLocation("smoothblocks/entity_test")
                .withVertexShader("core/entity").withFragmentShader("core/entity")
                .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS).build();
        SmoothBlocksDrawState pass = new SmoothBlocksDrawState() {
            public RenderPipeline smoothblocks$pipeline() { return pipeline; }
            public GpuTexture smoothblocks$diffuse() { return texture; }
        };
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture.glId());
        glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
        SmoothBlocksRenderScope.beginWorld();
        SmoothBlocksEntityXbrzGpuBridge.configureActiveEntityProgram(pass);
        require(Arrays.equals(world, readDraw()), "Real bridge must enable world reconstruction");
        SmoothBlocksEntityXbrzGpuBridge.release();
        // Same shader AND same texture as the world draw; only context changes.
        SmoothBlocksRenderScope.endWorld();
        SmoothBlocksEntityXbrzGpuBridge.configureActiveEntityProgram(pass);
        require(Arrays.equals(baseline, readDraw()), "Real bridge must disable shared shader in GUI");
        SmoothBlocksEntityXbrzGpuBridge.release();
        GpuSampler auxiliary = new GpuSampler() {
            public AddressMode getAddressModeU() { return AddressMode.REPEAT; }
            public AddressMode getAddressModeV() { return AddressMode.REPEAT; }
            public FilterMode getMinFilter() { return FilterMode.LINEAR; }
            public FilterMode getMagFilter() { return FilterMode.LINEAR; }
            public int getMaxAnisotropy() { return 1; }
            public java.util.OptionalDouble getMaxLod() { return java.util.OptionalDouble.empty(); }
            public void close() {}
        };
        require(SmoothBlocksClient.chooseWorldEntitySampler(pipeline, "Sampler0", auxiliary) == auxiliary,
                "GUI sampler must remain original");
        SmoothBlocksRenderScope.beginWorld();
        require(SmoothBlocksClient.chooseWorldEntitySampler(pipeline, "Sampler1", auxiliary) == auxiliary,
                "World lightmap/auxiliary sampler must remain original");
        SmoothBlocksRenderScope.endWorld();
    }

    private static void boundaryAndBinding(int entity) {
        int[] atlas = new int[32];
        short[] packed = new short[32];
        for (int sprite = 0; sprite < 2; sprite++) {
            int[] local = new int[16];
            Arrays.fill(local, sprite == 0 ? 0xFFFF0000 : 0xFF0000FF);
            short[] states = SmoothBlocksXbrzCore.classifyArgb(4, 4, local).metadata();
            for (int y = 0; y < 4; y++) {
                System.arraycopy(local, y * 4, atlas, y * 8 + sprite * 4, 4);
                System.arraycopy(states, y * 4, packed, y * 8 + sprite * 4, 4);
            }
        }
        ByteBuffer bytes = BufferUtils.createByteBuffer(packed.length * 2);
        for (short value : packed) bytes.put((byte) value).put((byte) (value >>> 8));
        bytes.flip();
        int metadata = SmoothBlocksXbrzMetadata.uploadMetadata(8, 4, bytes);
        int source = upload(atlas, 8, 4);
        byte[] result = draw(entity, source, metadata, "Entity", 2);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int offset = (y * SIZE + x) * 4;
            require((result[offset + (x < SIZE / 2 ? 0 : 2)] & 255) == 255, "Sprite boundary contamination");
        }
        glUseProgram(entity);
        int location = glGetUniformLocation(entity, "smoothblocks_EntityMeta");
        int[] samplers = SmoothBlocksMetadataBinding.findSamplerLocations(entity, location);
        int chosen = Math.min(glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS), glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)) - 1;
        glActiveTexture(GL_TEXTURE0 + chosen);
        glBindTexture(GL_TEXTURE_2D, source);
        int sampler = glGenSamplers();
        glBindSampler(chosen, sampler);
        glActiveTexture(GL_TEXTURE0);
        SmoothBlocksMetadataBinding binding = new SmoothBlocksMetadataBinding();
        require(binding.bind(entity, samplers, metadata) == chosen, "Unused unit selection");
        require(glGetInteger(GL_ACTIVE_TEXTURE) == GL_TEXTURE0, "Active unit must be preserved");
        binding.release();
        glActiveTexture(GL_TEXTURE0 + chosen);
        require(glGetInteger(GL_TEXTURE_BINDING_2D) == source && glGetInteger(GL_SAMPLER_BINDING) == sampler, "Bindings must be restored");
        glActiveTexture(GL_TEXTURE0);
        glDeleteSamplers(sampler);
        glDeleteTextures(metadata);
        glDeleteTextures(source);
    }

    private static byte[] draw(int program, int texture, int metadata, String prefix, int mode) {
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, metadata);
        glUniform1i(glGetUniformLocation(program, "smoothblocks_" + prefix + "Meta"), 1);
        glUniform1i(glGetUniformLocation(program, "smoothblocks_" + prefix + "Mode"), mode);
        glActiveTexture(GL_TEXTURE0);
        return readDraw();
    }

    private static byte[] readDraw() {
        glDrawArrays(GL_TRIANGLES, 0, 3);
        ByteBuffer bytes = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
        byte[] output = new byte[bytes.remaining()];
        bytes.get(output);
        return output;
    }

    private static void animatedFrames(int program, int source, int[] first, byte[] expected) {
        var id = net.minecraft.resources.Identifier.withDefaultNamespace("block/test_animation");
        var image = new com.mojang.blaze3d.platform.NativeImage(8, 16, true);
        int[] second = new int[64];
        for (int i = 0; i < 64; i++) {
            second[i] = first[63 - i];
            image.setPixel(i % 8, i / 8, first[i]);
            image.setPixel(i % 8, 8 + i / 8, second[i]);
        }
        var animation = new net.minecraft.client.resources.metadata.animation.AnimationMetadataSection(
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), 2, true);
        try (var contents = new net.minecraft.client.renderer.texture.SpriteContents(id,
                new net.minecraft.client.resources.metadata.animation.FrameSize(8, 8), image,
                java.util.Optional.of(animation), java.util.List.of(), java.util.Optional.empty())) {
            class Sprite extends net.minecraft.client.renderer.texture.TextureAtlasSprite {
                Sprite() { super(id, contents, 8, 8, 0, 0, 0); }
            }
            var sprite = new Sprite();
            var preparation = new net.minecraft.client.renderer.texture.SpriteLoader.Preparations(
                    8, 8, 0, sprite, java.util.Map.of(id, sprite), java.util.concurrent.CompletableFuture.completedFuture(null));
            short[] extra = SmoothBlocksAnimatedMetadata.prepare(preparation);
            require(extra.length <= 6 + 4 * 64, "Duplicate frame metadata shares storage");
            require(SmoothBlocksAnimatedMetadata.frameAddress(sprite, 0, 1) >= 6
                    && SmoothBlocksAnimatedMetadata.frameAddress(sprite, 1, 1) >= 6, "Temporal intermediate steps precomputed");
            int height = 8 + (extra.length + 7) / 8;
            ByteBuffer bytes = BufferUtils.createByteBuffer(8 * height * 2);
            for (int i = 0; i < 64; i++) bytes.putShort(i * 2, (short) SmoothBlocksAnimatedMetadata.marker(sprite));
            for (int i = 0; i < extra.length; i++) bytes.putShort((64 + i) * 2, extra[i]);
            int animated = SmoothBlocksXbrzMetadata.uploadMetadata(8, height, bytes);
            require(Arrays.equals(expected, draw(program, source, animated, "Entity", 2)), "Frame pointer resolves identical xBRZ");
            ByteBuffer pointer = BufferUtils.createByteBuffer(4);
            int nextAddress = SmoothBlocksAnimatedMetadata.frameAddress(sprite, 1, 0);
            pointer.putInt(0, nextAddress);
            SmoothBlocksXbrzMetadata.updatePointer(animated, 4, 8, 8, pointer);
            writePixels(source, second, 8, 8);
            short[] direct = SmoothBlocksXbrzCore.classifyArgb(8, 8, second).metadata();
            ByteBuffer directBytes = BufferUtils.createByteBuffer(128);
            for (short value : direct) directBytes.putShort(value);
            directBytes.flip();
            int reference = SmoothBlocksXbrzMetadata.uploadMetadata(8, 8, directBytes);
            require(Arrays.equals(draw(program, source, reference, "Entity", 2),
                    draw(program, source, animated, "Entity", 2)), "Four-byte pointer switches to the second frame");
            writePixels(source, first, 8, 8);
            glDeleteTextures(animated); glDeleteTextures(reference);
            SmoothBlocksAnimatedMetadata.clear();
        }
    }

    private static void periodicBlock() {
        int[] tile = new int[64], atlas = new int[128], repeated = new int[24 * 24];
        Arrays.fill(atlas, 0xFFFF00FF);
        var image = new com.mojang.blaze3d.platform.NativeImage(8, 8, true);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int color = (x + y) % 8 < 3 ? 0xFFFFAA33 : 0xFF224488;
            tile[y * 8 + x] = color; atlas[y * 16 + x] = color; image.setPixel(x, y, color);
        }
        for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) repeated[y * 24 + x] = tile[(y % 8) * 8 + x % 8];
        short[] periodic = SmoothBlocksXbrzCore.classifyArgb(8, 8, tile, true).metadata();
        short[] reference = SmoothBlocksXbrzCore.classifyArgb(24, 24, repeated).metadata();
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
            require((periodic[y * 8 + x] & 0xFFF) == reference[(y + 8) * 24 + x + 8], "Periodic classifier matches repeated image");
        var id = net.minecraft.resources.Identifier.withDefaultNamespace("block/test_periodic");
        try (var contents = new net.minecraft.client.renderer.texture.SpriteContents(id,
                new net.minecraft.client.resources.metadata.animation.FrameSize(8, 8), image)) {
            class Sprite extends net.minecraft.client.renderer.texture.TextureAtlasSprite {
                Sprite() { super(id, contents, 16, 8, 0, 0, 0); }
            }
            var sprite = new Sprite();
            var preparations = new net.minecraft.client.renderer.texture.SpriteLoader.Preparations(16, 8, 0,
                    sprite, java.util.Map.of(id, sprite), java.util.concurrent.CompletableFuture.completedFuture(null));
            short[] extra = SmoothBlocksAnimatedMetadata.prepare(preparations, atlas);
            ByteBuffer metadata = BufferUtils.createByteBuffer(16 * (8 + (extra.length + 15) / 16) * 2);
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) metadata.putShort((y * 16 + x) * 2,
                    (short) (x == 0 || y == 0 || x == 7 || y == 7 ? SmoothBlocksAnimatedMetadata.marker(sprite) : periodic[y * 8 + x]));
            for (int i = 0; i < extra.length; i++) metadata.putShort((128 + i) * 2, extra[i]);
            int meta = SmoothBlocksXbrzMetadata.uploadMetadata(16, metadata.capacity() / 32, metadata);
            ByteBuffer refBytes = BufferUtils.createByteBuffer(reference.length * 2);
            for (short value : reference) refBytes.putShort(value);
            refBytes.flip();
            int refMeta = SmoothBlocksXbrzMetadata.uploadMetadata(24, 24, refBytes);
            int shader = program(SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect("minecraft:core/entity",
                    FRAGMENT.replace("texture(Sampler0, uv)", "texture(Sampler0, uv * vec2(0.5, 1.0))")));
            int refShader = program(SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect("minecraft:core/entity",
                    FRAGMENT.replace("texture(Sampler0, uv)", "texture(Sampler0, (uv + vec2(1.0)) / 3.0)")));
            int source = upload(atlas, 16, 8), refSource = upload(repeated, 24, 24);
            require(Arrays.equals(draw(shader, source, meta, "Entity", 2), draw(refShader, refSource, refMeta, "Entity", 2)),
                    "Periodic GPU output matches tiled reference and excludes adjacent atlas sprite");
            glDeleteTextures(meta); glDeleteTextures(refMeta); glDeleteTextures(source); glDeleteTextures(refSource);
            glDeleteProgram(shader); glDeleteProgram(refShader); SmoothBlocksAnimatedMetadata.clear();
        }
    }

    private static int upload(int[] pixels, int width, int height) {
        int texture = glGenTextures();
        writePixels(texture, pixels, width, height);
        return texture;
    }

    private static void writePixels(int texture, int[] pixels, int width, int height) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        ByteBuffer bytes = BufferUtils.createByteBuffer(pixels.length * 4);
        for (int p : pixels) bytes.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >> 24));
        bytes.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
    }

    private static int program(String fragment) {
        int program = glCreateProgram();
        for (int type : new int[]{GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(type);
            glShaderSource(shader, type == GL_VERTEX_SHADER ? VERTEX : fragment);
            glCompileShader(shader);
            require(glGetShaderi(shader, GL_COMPILE_STATUS) != 0, glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        require(glGetProgrami(program, GL_LINK_STATUS) != 0, glGetProgramInfoLog(program));
        return program;
    }

    private static final class TestTexture extends GlTexture {
        TestTexture(int id, int width, int height) { super(USAGE_TEXTURE_BINDING, "regression", TextureFormat.RGBA8, width, height, 1, 1, id); }
    }
}
