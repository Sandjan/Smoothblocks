package de.oai.smoothblocks.mixin;

import de.oai.smoothblocks.SmoothBlocksClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    private boolean smoothblocks$p;
    private boolean smoothblocks$f6;
    private boolean smoothblocks$f7;
    private boolean smoothblocks$f8;
    private boolean smoothblocks$f9;

    @Inject(method = "tick", at = @At("TAIL"))
    private void smoothblocks$poll(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            smoothblocks$resetStates(minecraft);
            return;
        }

        long handle = minecraft.getWindow().handle();
        boolean p = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
        boolean f6 = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F6) == GLFW.GLFW_PRESS;
        boolean f7 = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F7) == GLFW.GLFW_PRESS;
        boolean f8 = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
        boolean f9 = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F9) == GLFW.GLFW_PRESS;

        if (p && !smoothblocks$p) SmoothBlocksClient.toggleFilter(minecraft);
        if (f6 && !smoothblocks$f6) SmoothBlocksClient.toggleDebugView(minecraft);
        if (f7 && !smoothblocks$f7) SmoothBlocksClient.showDebugReport(minecraft);
        if (f8 && !smoothblocks$f8) SmoothBlocksClient.toggleEnabled(minecraft);
        if (f9 && !smoothblocks$f9) SmoothBlocksClient.reset(minecraft);

        smoothblocks$p = p;
        smoothblocks$f6 = f6;
        smoothblocks$f7 = f7;
        smoothblocks$f8 = f8;
        smoothblocks$f9 = f9;
    }

    private void smoothblocks$resetStates(Minecraft minecraft) {
        smoothblocks$p = false;
        smoothblocks$f6 = false;
        smoothblocks$f7 = false;
        smoothblocks$f8 = false;
        smoothblocks$f9 = false;
    }
}
