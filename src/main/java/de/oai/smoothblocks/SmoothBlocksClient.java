package de.oai.smoothblocks;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import net.fabricmc.loader.api.FabricLoader;

/** Fixed rendering policy and ownership of sampler variants created by this mod. */
public final class SmoothBlocksClient {
    public static final int SHADER_MODE_NEAREST = 0;
    public static final int SHADER_MODE_LINEAR = 1;
    public static final int SHADER_MODE_XBRZ = 2;

    private enum Category { WORLD_ENTITY, PARTICLE, OTHER }
    private record SamplerKey(AddressMode u, AddressMode v, FilterMode filter, int anisotropy, OptionalDouble lod) {}

    private static final Map<SamplerKey, GpuSampler> SAMPLERS = new HashMap<>();
    private static final Map<RenderPipeline, Category> CATEGORIES = new IdentityHashMap<>();

    private SmoothBlocksClient() {}

    public static void init() {
        System.out.println("[SmoothBlocks] Loaded. Minecraft=" + modVersion("minecraft")
                + ", Iris=" + modVersion("iris") + ", Sodium=" + modVersion("sodium"));
    }

    /** Shared Minecraft samplers are borrowed; only variants in this map are owned here. */
    public static void closeCaches() {
        SAMPLERS.values().forEach(GpuSampler::close);
        SAMPLERS.clear();
        CATEGORIES.clear();
    }

    /** Only world Sampler0 is eligible; particles retain their established linear path. */
    public static GpuSampler chooseWorldEntitySampler(RenderPipeline pipeline, String name, GpuSampler original) {
        if (!SmoothBlocksRenderScope.isWorld() || !"Sampler0".equals(name)) return original;
        return switch (category(pipeline)) {
            case WORLD_ENTITY -> filteredSampler(original, FilterMode.NEAREST);
            case PARTICLE -> filteredSampler(original, FilterMode.LINEAR);
            default -> original;
        };
    }

    private static GpuSampler filteredSampler(GpuSampler original, FilterMode filter) {
        if (original.getMinFilter() == filter && original.getMagFilter() == filter) return original;
        return sampler(new SamplerKey(original.getAddressModeU(), original.getAddressModeV(), filter,
                original.getMaxAnisotropy(), original.getMaxLod()));
    }

    private static GpuSampler sampler(SamplerKey key) {
        return SAMPLERS.computeIfAbsent(key, k -> RenderSystem.getDevice().createSampler(
                k.u, k.v, k.filter, k.filter, k.anisotropy, k.lod));
    }

    /** Iris terrain variants share the same disposal owner as entity variants. */
    public static GpuSampler chooseIrisTerrainSampler(GpuSampler original) {
        int anisotropy = Math.clamp(original.getMaxAnisotropy(), 1, 32);
        return sampler(new SamplerKey(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, anisotropy, OptionalDouble.empty()));
    }

    public static GpuSampler chooseSodiumTerrainSampler(GpuSampler original) {
        if (!RenderSystem.isOnRenderThread()) return original;
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }

    public static boolean isWorldEntityPipeline(RenderPipeline pipeline) {
        return category(pipeline) == Category.WORLD_ENTITY;
    }

    private static Category category(RenderPipeline pipeline) {
        return CATEGORIES.computeIfAbsent(pipeline, SmoothBlocksClient::classifyPipeline);
    }

    /** Specific exclusions precede broad entity/item names; third-party pipelines use the same naming policy. */
    private static Category classifyPipeline(RenderPipeline pipeline) {
        String name = pipeline == null ? "none" : String.valueOf(pipeline.getLocation());
        if (name.contains("particle")) return Category.PARTICLE;
        if (name.contains("weather")) return Category.OTHER;
        if (name.contains("gui") || name.contains("text") || name.contains("crosshair")
                || name.contains("vignette") || name.contains("screen_effect")) return Category.OTHER;
        if (name.contains("terrain") || name.contains("solid_block") || name.contains("cutout_block")
                || name.contains("tripwire_block") || name.contains("translucent_moving_block")) return Category.OTHER;
        if (name.contains("entity") || name.contains("item") || name.contains("armor") || name.contains("eyes")
                || name.contains("energy_swirl") || name.contains("breeze_wind") || name.contains("moving_block")) {
            return Category.WORLD_ENTITY;
        }
        return Category.OTHER;
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("absent");
    }
}
