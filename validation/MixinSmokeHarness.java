package de.oai.smoothblocks;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Loads real target bytecode through Fabric/Mixin, then exits before game startup. */
public final class MixinSmokeHarness implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            for (String name : new String[]{
                    "net.minecraft.client.resources.model.geometry.BakedQuad",
                    "net.minecraft.client.resources.model.geometry.QuadCollection$Builder",
                    "net.minecraft.client.resources.model.cuboid.ItemModelGenerator",
                    "net.minecraft.client.renderer.feature.ItemFeatureRenderer",
                    "net.minecraft.client.renderer.ShaderManager$CompilationCache",
                    "net.minecraft.client.renderer.GameRenderer",
                    "net.minecraft.client.gui.render.GuiRenderer",
                    "com.mojang.blaze3d.systems.RenderPass",
                    "com.mojang.blaze3d.opengl.GlRenderPass",
                    "com.mojang.blaze3d.opengl.GlCommandEncoder",
                    "com.mojang.blaze3d.opengl.GlTexture",
                    "net.minecraft.client.renderer.texture.ReloadableTexture",
                    "net.minecraft.client.renderer.texture.TextureAtlas",
                    "net.minecraft.client.renderer.texture.TextureManager"}) {
                Class<?> cls = Class.forName(name, false, getClass().getClassLoader());
                boolean injected = java.util.Arrays.stream(cls.getDeclaredMethods())
                        .anyMatch(method -> method.getName().contains("smoothblocks"));
                if (!injected) throw new AssertionError("No injected methods: " + name);
            }
            if (Boolean.getBoolean("smoothblocks.testSodiumItems")) verifyItemBake();
            System.out.println("PASS Fabric/Mixin: all vanilla target classes transformed and verified; no game window launched");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void verifyItemBake() throws Exception {
        Class.forName("net.caffeinemc.mods.sodium.client.render.frapi.render.ItemRenderContext", false,
                MixinSmokeHarness.class.getClassLoader());
        var image = new com.mojang.blaze3d.platform.NativeImage(2, 2, true);
        image.setPixel(0, 0, 0xffffffff);
        var id = net.minecraft.resources.Identifier.withDefaultNamespace("test_item");
        try (var contents = new net.minecraft.client.renderer.texture.SpriteContents(id,
                new net.minecraft.client.resources.metadata.animation.FrameSize(2, 2), image)) {
            var ctor = net.minecraft.client.renderer.texture.TextureAtlasSprite.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            var sprite = (net.minecraft.client.renderer.texture.TextureAtlasSprite)
                    ctor.newInstance(id, contents, 2, 2, 0, 0, 0);
            var material = new net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo(
                    sprite, null, null, 0, false, 0);
            var interner = new net.minecraft.client.resources.model.ModelBaker.Interner() {
                public org.joml.Vector3fc vector(org.joml.Vector3fc v) { return v; }
                public net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo materialInfo(
                        net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo m) { return m; }
            };
            var builder = new net.minecraft.client.resources.model.geometry.QuadCollection.Builder();
            var bake = Class.forName("net.minecraft.client.resources.model.cuboid.ItemModelGenerator")
                    .getDeclaredMethod("bakeExtrudedSprite", builder.getClass(),
                            net.minecraft.client.resources.model.ModelBaker.Interner.class,
                            net.minecraft.client.renderer.block.dispatch.ModelState.class, material.getClass());
            bake.setAccessible(true);
            bake.invoke(null, builder, interner,
                    net.minecraft.client.renderer.block.dispatch.BlockModelRotation.IDENTITY, material);
            var quads = builder.build().getAll();
            SmoothBlocksRenderScope.beginWorld();
            long retained = quads.stream().filter(q -> !SmoothBlocksGeneratedSide.shouldOmit(q)).count();
            if (quads.size() <= 2 || retained != 2) throw new AssertionError("Wrong retained faces: " + retained);
            SmoothBlocksRenderScope.endWorld();
            if (quads.stream().anyMatch(SmoothBlocksGeneratedSide::shouldOmit))
                throw new AssertionError("GUI geometry changed");
            System.out.println("PASS real Sodium item bake: both faces retained, all " + (quads.size() - 2)
                    + " generated walls omitted in world only");
        }
    }
}
