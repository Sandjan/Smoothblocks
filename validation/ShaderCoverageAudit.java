import de.oai.smoothblocks.SmoothBlocksShaderPatch;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

/** Read-only coverage audit against the actual Minecraft jar; not a rendering test. */
public class ShaderCoverageAudit {
    public static void main(String[] args) throws Exception {
        try (var jar = new ZipFile(args[0])) {
            for (String name : new String[]{"entity", "item"}) {
                var entry = jar.getEntry("assets/minecraft/shaders/core/" + name + ".fsh");
                if (entry == null) throw new IllegalStateException("Missing shader: " + name);
                String source;
                try (var in = jar.getInputStream(entry)) {
                    source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                String patched = SmoothBlocksShaderPatch.patchVanillaEntityFragmentDirect(
                        "minecraft:core/" + name, source);
                System.out.println(name + ": selected=" + SmoothBlocksShaderPatch.isEntityShaderName(
                        "minecraft:core/" + name) + " patched=" + !source.equals(patched));
            }
        }
    }
}
