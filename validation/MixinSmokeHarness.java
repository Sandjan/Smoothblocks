package de.oai.smoothblocks;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Loads real target bytecode through Fabric/Mixin, then exits before game startup. */
public final class MixinSmokeHarness implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            for (String name : new String[]{
                    "net.minecraft.client.resources.model.geometry.BakedQuad",
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
            System.out.println("PASS Fabric/Mixin: all vanilla target classes transformed and verified; no game window launched");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
