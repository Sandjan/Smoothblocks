package de.oai.smoothblocks;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.OptionalDouble;
import com.mojang.blaze3d.textures.AddressMode;
import java.util.concurrent.atomic.AtomicLong;

/** Client state, mode switching and diagnostics. */
public final class SmoothBlocksClient {
    private SmoothBlocksClient() {}

    public enum SamplingMode {
        NEAREST,
        LINEAR,
        XBRZ
    }

    /** F6 diagnostic view. OFF never changes rendered colors. */
    public enum DebugView {
        OFF,
        PATH,
        EDGES
    }

    public static final int SHADER_MODE_NEAREST = 0;
    public static final int SHADER_MODE_LINEAR = 1;
    public static final int SHADER_MODE_XBRZ = 2;

    private static volatile boolean enabled = true;
    private static volatile SamplingMode selectedMode = SamplingMode.XBRZ;
    private static volatile DebugView debugView = DebugView.OFF;
    private static volatile boolean verbose = false;

    private static volatile GpuSampler linearSampler;
    private static volatile GpuSampler nearestSampler;
    private record SamplerKey(AddressMode u, AddressMode v, FilterMode filter, int anisotropy, OptionalDouble lod) {}
    private static final Map<SamplerKey, GpuSampler> ENTITY_SAMPLERS = new HashMap<>();
    private static final Map<GpuSampler, String> FILTER_DESCRIPTIONS = new IdentityHashMap<>();
    private static final Map<RenderPipeline, String> PIPELINE_NAMES = new IdentityHashMap<>();

    public static void closeCaches() {
        for (GpuSampler sampler : ENTITY_SAMPLERS.values()) sampler.close();
        ENTITY_SAMPLERS.clear();
        FILTER_DESCRIPTIONS.clear();
        PIPELINE_NAMES.clear();
        CATEGORIES.clear();
    }

    private static GpuSampler filteredSampler(GpuSampler original, FilterMode filter) {
        if (original.getMinFilter() == filter && original.getMagFilter() == filter) return original;
        SamplerKey key = new SamplerKey(original.getAddressModeU(), original.getAddressModeV(), filter,
                original.getMaxAnisotropy(), original.getMaxLod());
        return ENTITY_SAMPLERS.computeIfAbsent(key, k -> RenderSystem.getDevice().createSampler(
                k.u, k.v, k.filter, k.filter, k.anisotropy, k.lod));
    }

    private static volatile String lastPipeline = "none";
    private static volatile String lastTextureName = "none";
    private static volatile String lastTextureLabel = "none";
    private static volatile String lastOriginalFilter = "none";
    private static volatile String lastAppliedFilter = "none";
    private static volatile String lastSodiumOriginalFilter = "none";
    private static volatile String lastSodiumAppliedFilter = "none";
    private static volatile int lastXbrzTextureUnit = -1;
    private static volatile String lastMetadataBuild = "none";
    private static volatile String lastXbrzBindStatus = "none";
    private static volatile String lastEntityXbrzStatus = "none";

    public static final AtomicLong PIPELINE_CALLS = new AtomicLong();
    public static final AtomicLong BIND_CALLS = new AtomicLong();
    public static final AtomicLong SELECTED_BIND_CALLS = new AtomicLong();
    public static final AtomicLong SELECTED_FORCED_CALLS = new AtomicLong();
    public static final AtomicLong SODIUM_TERRAIN_CALLS = new AtomicLong();
    public static final AtomicLong SODIUM_TERRAIN_FORCED = new AtomicLong();
    public static final AtomicLong IRIS_SODIUM_TERRAIN_CALLS = new AtomicLong();
    public static final AtomicLong IRIS_TERRAIN_SHADER_PATCHES = new AtomicLong();
    public static final AtomicLong DEFAULT_TERRAIN_SHADER_PATCHES = new AtomicLong();
    public static final AtomicLong IRIS_ENTITY_SHADER_PATCHES = new AtomicLong();
    public static final AtomicLong DEFAULT_ENTITY_SHADER_PATCHES = new AtomicLong();
    public static final AtomicLong ENTITY_XBRZ_PROGRAM_SETUPS = new AtomicLong();
    public static final AtomicLong ENTITY_XBRZ_ACTIVE_SETUPS = new AtomicLong();
    public static final AtomicLong PARTICLE_BIND_CALLS = new AtomicLong();
    public static final AtomicLong PARTICLE_FORCED_CALLS = new AtomicLong();
    public static final AtomicLong EXCLUDED_WEATHER_CALLS = new AtomicLong();
    public static final AtomicLong ENTITY_ALPHA_REPAIR_TEXTURES = new AtomicLong();
    public static final AtomicLong ENTITY_ALPHA_REPAIR_PIXELS = new AtomicLong();
    public static final AtomicLong EXCLUDED_GUI_CALLS = new AtomicLong();
    public static final AtomicLong EXCLUDED_OTHER_CALLS = new AtomicLong();
    public static final AtomicLong XBRZ_METADATA_BUILDS = new AtomicLong();
    public static final AtomicLong XBRZ_PROGRAM_BINDS = new AtomicLong();
    public static final AtomicLong XBRZ_ACTIVE_BINDS = new AtomicLong();
    public static final AtomicLong XBRZ_FALLBACK_BINDS = new AtomicLong();
    public static final AtomicLong XBRZ_FALLBACK_PATCH = new AtomicLong();
    public static final AtomicLong XBRZ_FALLBACK_META = new AtomicLong();
    public static final AtomicLong XBRZ_FALLBACK_UNIT = new AtomicLong();

    public static void init() {
        ensureSamplers();
        logEnvironment();
    }

    private static void ensureSamplers() {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        var cache = RenderSystem.getSamplerCache();
        linearSampler = cache.getClampToEdge(FilterMode.LINEAR);
        nearestSampler = cache.getClampToEdge(FilterMode.NEAREST);
    }

    private static void ensureSamplersIfNeeded() {
        if (linearSampler == null || nearestSampler == null) {
            ensureSamplers();
        }
    }

    private static GpuSampler linear() {
        ensureSamplersIfNeeded();
        return linearSampler;
    }

    private static GpuSampler nearest() {
        ensureSamplersIfNeeded();
        return nearestSampler;
    }

    public static void notePipeline(RenderPipeline pipeline) {
        PIPELINE_CALLS.incrementAndGet();
        lastPipeline = pipelineName(pipeline);
    }

    public static void observeBind(RenderPipeline pipeline, String name, GpuTextureView view, GpuSampler sampler) {
        BIND_CALLS.incrementAndGet();
        lastPipeline = pipelineName(pipeline);
        lastTextureName = name == null ? "null" : name;
        lastTextureLabel = view == null || view.texture() == null ? "null" : view.texture().getLabel();
        lastOriginalFilter = filterDescription(sampler);

        Category category = category(pipeline);
        switch (category) {
            case WORLD_ENTITY, TERRAIN -> SELECTED_BIND_CALLS.incrementAndGet();
            case PARTICLE -> PARTICLE_BIND_CALLS.incrementAndGet();
            case WEATHER -> EXCLUDED_WEATHER_CALLS.incrementAndGet();
            case GUI -> EXCLUDED_GUI_CALLS.incrementAndGet();
            case OTHER -> EXCLUDED_OTHER_CALLS.incrementAndGet();
        }

        if (verbose) {
            System.out.printf(Locale.ROOT,
                    "[SmoothBlocks] bind pipeline=%s category=%s name=%s filter=%s%n",
                    lastPipeline, category, lastTextureName, lastOriginalFilter);
        }
    }

    /** Entity XBRZ reconstructs with texelFetch; keep a NEAREST backing sampler so an unpatched texture() call cannot silently introduce linear filtering. */
    public static GpuSampler chooseWorldEntitySampler(RenderPipeline pipeline, String name, GpuSampler original) {
        if (!SmoothBlocksRenderScope.isWorld() || !"Sampler0".equals(name)) return original;
        Category category = category(pipeline);
        if (!enabled || (category != Category.WORLD_ENTITY && category != Category.PARTICLE)) {
            return original;
        }

        GpuSampler replacement;
        if (category == Category.PARTICLE && selectedMode == SamplingMode.XBRZ) {
            // Particles are not part of the xBRZ guarantee; preserve the known-good smooth path.
            replacement = filteredSampler(original, FilterMode.LINEAR);
        } else {
            replacement = filteredSampler(original, getTerrainSamplerFilter());
        }
        if (replacement != null && replacement != original) {
            SELECTED_FORCED_CALLS.incrementAndGet();
            if (category == Category.PARTICLE) {
                PARTICLE_FORCED_CALLS.incrementAndGet();
            }
        }
        if (replacement == null) replacement = original;
        lastAppliedFilter = filterDescription(replacement);
        return replacement;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static SamplingMode getSelectedMode() {
        return selectedMode;
    }

    /**
     * XBRZ uses texelFetch and therefore does not need hardware filtering. Keep its
     * backing sampler NEAREST so any texture() call missed by a third-party shader
     * patch cannot masquerade as LINEAR inside a static sprite. Animated terrain is
     * bilinear-filtered explicitly in the injected shader, independent of sampler state.
     */
    public static FilterMode getTerrainSamplerFilter() {
        return selectedMode == SamplingMode.LINEAR ? FilterMode.LINEAR : FilterMode.NEAREST;
    }

    /** Compatibility name used by the Iris sampler mixin. */
    public static FilterMode getSelectedFilter() {
        return getTerrainSamplerFilter();
    }

    public static int getShaderModeCode() {
        if (!enabled) return SHADER_MODE_NEAREST;
        return switch (selectedMode) {
            case NEAREST -> SHADER_MODE_NEAREST;
            case LINEAR -> SHADER_MODE_LINEAR;
            case XBRZ -> SHADER_MODE_XBRZ;
        };
    }

    public static int getDebugViewCode() {
        if (!enabled || selectedMode != SamplingMode.XBRZ) return 0;
        return switch (debugView) {
            case OFF -> 0;
            case PATH -> 1;
            case EDGES -> 2;
        };
    }

    public static DebugView getDebugView() {
        return debugView;
    }

    public static void noteIrisSodiumTerrainApplied(GpuSampler sampler) {
        lastSodiumAppliedFilter = filterDescription(sampler);
        lastAppliedFilter = lastSodiumAppliedFilter;
    }

    public static void noteIrisSodiumTerrainSampler(GpuSampler original) {
        IRIS_SODIUM_TERRAIN_CALLS.incrementAndGet();
        lastSodiumOriginalFilter = filterDescription(original);
        if (enabled) {
            FilterMode filter = getTerrainSamplerFilter();
            lastSodiumAppliedFilter = filter + "/" + filter
                    + " aniso=" + (original == null ? 1 : original.getMaxAnisotropy());
        } else {
            lastSodiumAppliedFilter = lastSodiumOriginalFilter;
        }
        lastAppliedFilter = lastSodiumAppliedFilter;
    }

    public static void noteIrisTerrainShaderPatched() {
        IRIS_TERRAIN_SHADER_PATCHES.incrementAndGet();
        // Iris can rebuild GL programs when a shader pack is toggled without
        // necessarily rebuilding the block atlas. Avoid stale uniform locations
        // if OpenGL later reuses a program id.
        SmoothBlocksXbrzGpuBridge.clearProgramCache();
    }

    public static void noteDefaultSodiumShaderPatched() {
        DEFAULT_TERRAIN_SHADER_PATCHES.incrementAndGet();
        SmoothBlocksXbrzGpuBridge.clearProgramCache();
    }

    public static void noteIrisEntityShaderPatched() {
        IRIS_ENTITY_SHADER_PATCHES.incrementAndGet();
        SmoothBlocksEntityXbrzGpuBridge.clearProgramCache();
    }

    public static void noteDefaultEntityShaderPatched() {
        DEFAULT_ENTITY_SHADER_PATCHES.incrementAndGet();
        SmoothBlocksEntityXbrzGpuBridge.clearProgramCache();
    }

    public static void noteEntityXbrzProgramSetup(int mode) {
        ENTITY_XBRZ_PROGRAM_SETUPS.incrementAndGet();
        if (mode == SHADER_MODE_XBRZ) {
            ENTITY_XBRZ_ACTIVE_SETUPS.incrementAndGet();
            lastEntityXbrzStatus = "active";
        } else if (!enabled) {
            lastEntityXbrzStatus = "off";
        } else {
            lastEntityXbrzStatus = selectedMode.name().toLowerCase(Locale.ROOT);
        }
    }

    public static GpuSampler chooseSodiumTerrainSampler(GpuSampler original) {
        SODIUM_TERRAIN_CALLS.incrementAndGet();
        lastSodiumOriginalFilter = filterDescription(original);
        if (!enabled) {
            lastSodiumAppliedFilter = lastSodiumOriginalFilter;
            lastAppliedFilter = lastSodiumAppliedFilter;
            return original;
        }

        GpuSampler replacement = getTerrainSamplerFilter() == FilterMode.NEAREST ? nearest() : linear();
        if (replacement == null) replacement = original;
        lastSodiumAppliedFilter = filterDescription(replacement);
        lastAppliedFilter = lastSodiumAppliedFilter;
        if (replacement != original) {
            SODIUM_TERRAIN_FORCED.incrementAndGet();
        }
        return replacement;
    }

    public static void noteXbrzMetadataBuilt(int width, int height, long sprites, long texels,
                                              long animated, long nanos) {
        XBRZ_METADATA_BUILDS.incrementAndGet();
        lastMetadataBuild = String.format(Locale.ROOT,
                "%dx%d static=%d anim=%d",
                width, height, sprites, animated);
    }

    public static void noteXbrzProgramBind(int requestedMode, int effectiveMode, int unit, String status) {
        XBRZ_PROGRAM_BINDS.incrementAndGet();
        if (requestedMode == SHADER_MODE_XBRZ) {
            if (status != null) lastXbrzBindStatus = status;
            // Report the unit for the most recent bind attempt. v1.5.5 kept an old
            // successful unit here even when the current pass had fallen back.
            lastXbrzTextureUnit = unit;
        }
        if (effectiveMode == SHADER_MODE_XBRZ) {
            XBRZ_ACTIVE_BINDS.incrementAndGet();
        } else if (requestedMode == SHADER_MODE_XBRZ) {
            XBRZ_FALLBACK_BINDS.incrementAndGet();
            if ("metadata-not-ready".equals(status)) {
                XBRZ_FALLBACK_META.incrementAndGet();
            } else if ("no-free-texture-unit".equals(status)) {
                XBRZ_FALLBACK_UNIT.incrementAndGet();
            } else {
                // no-current-program / shader-not-patched / meta-uniform-missing
                XBRZ_FALLBACK_PATCH.incrementAndGet();
            }
        }
    }

    private enum Category {
        WORLD_ENTITY,
        PARTICLE,
        WEATHER,
        GUI,
        TERRAIN,
        OTHER
    }

    private static final Map<RenderPipeline, Category> CATEGORIES = new IdentityHashMap<>();

    public static boolean isWorldEntityPipeline(RenderPipeline pipeline) {
        return category(pipeline) == Category.WORLD_ENTITY;
    }

    private static Category category(RenderPipeline pipeline) {
        return CATEGORIES.computeIfAbsent(pipeline, SmoothBlocksClient::classifyPipeline);
    }

    private static Category classifyPipeline(RenderPipeline pipeline) {
        String s = pipelineName(pipeline);
        if (s.contains("particle")) return Category.PARTICLE;
        if (s.contains("weather")) return Category.WEATHER;
        if (s.contains("gui") || s.contains("text") || s.contains("crosshair")
                || s.contains("vignette") || s.contains("screen_effect")) {
            return Category.GUI;
        }
        if (s.contains("terrain") || s.contains("solid_block") || s.contains("cutout_block")
                || s.contains("tripwire_block") || s.contains("translucent_moving_block")) {
            return Category.TERRAIN;
        }
        if (s.contains("entity") || s.contains("item") || s.contains("armor") || s.contains("eyes")
                || s.contains("energy_swirl") || s.contains("breeze_wind") || s.contains("moving_block")) {
            return Category.WORLD_ENTITY;
        }
        return Category.OTHER;
    }

    private static String pipelineName(RenderPipeline pipeline) {
        if (pipeline == null) return "none";
        return PIPELINE_NAMES.computeIfAbsent(pipeline, p -> String.valueOf(p.getLocation()));
    }

    private static String filterDescription(GpuSampler sampler) {
        if (sampler == null) return "null";
        return FILTER_DESCRIPTIONS.computeIfAbsent(sampler, s -> s.getMinFilter() + "/" + s.getMagFilter()
                + " aniso=" + s.getMaxAnisotropy() + " lod=" + s.getMaxLod());
    }

    public static void noteEntityAlphaEdgeRepair(int repairedPixels, String resourceId) {
        if (repairedPixels <= 0) return;
        ENTITY_ALPHA_REPAIR_TEXTURES.incrementAndGet();
        ENTITY_ALPHA_REPAIR_PIXELS.addAndGet(repairedPixels);
        if (verbose) {
            System.out.println("[SmoothBlocks] alpha-edge repair " + resourceId + " pixels=" + repairedPixels);
        }
    }

    public static void toggleEnabled(Minecraft minecraft) {
        enabled = !enabled;
        minecraft.gui.setOverlayMessage(Component.literal(
                "SmoothBlocks: " + (enabled ? selectedMode + "/ON" : "ORIGINAL/OFF")), true);
    }

    public static void toggleFilter(Minecraft minecraft) {
        selectedMode = switch (selectedMode) {
            case NEAREST -> SamplingMode.LINEAR;
            case LINEAR -> SamplingMode.XBRZ;
            case XBRZ -> SamplingMode.NEAREST;
        };
        minecraft.gui.setOverlayMessage(Component.literal(
                "SmoothBlocks: " + selectedMode), true);
    }

    /**
     * Compact visual diagnosis instead of console spam. PATH proves which metadata branch
     * a fragment hits; EDGES visualises the actual packed xBRZ edge states.
     */
    public static void toggleDebugView(Minecraft minecraft) {
        debugView = switch (debugView) {
            case OFF -> DebugView.PATH;
            case PATH -> DebugView.EDGES;
            case EDGES -> DebugView.OFF;
        };
        minecraft.gui.setOverlayMessage(Component.literal(
                "SmoothBlocks debug view: " + debugView), true);
    }

    public static void showDebugReport(Minecraft minecraft) {
        for (String line : debugText().split("\\n")) {
            minecraft.gui.getChat().addClientSystemMessage(Component.literal("[SmoothBlocks] " + line));
        }
    }

    public static void reset(Minecraft minecraft) {
        PIPELINE_CALLS.set(0);
        BIND_CALLS.set(0);
        SELECTED_BIND_CALLS.set(0);
        SELECTED_FORCED_CALLS.set(0);
        SODIUM_TERRAIN_CALLS.set(0);
        SODIUM_TERRAIN_FORCED.set(0);
        IRIS_SODIUM_TERRAIN_CALLS.set(0);
        // Shader patch counters are compile/reload-time evidence, not per-frame activity.
        // Keep them across F9; otherwise a reset after entering the world would falsely
        // report d/i=0/0 until the next resource or shader reload.
        ENTITY_XBRZ_PROGRAM_SETUPS.set(0);
        ENTITY_XBRZ_ACTIVE_SETUPS.set(0);
        PARTICLE_BIND_CALLS.set(0);
        PARTICLE_FORCED_CALLS.set(0);
        EXCLUDED_WEATHER_CALLS.set(0);
        EXCLUDED_GUI_CALLS.set(0);
        ENTITY_ALPHA_REPAIR_TEXTURES.set(0);
        ENTITY_ALPHA_REPAIR_PIXELS.set(0);
        EXCLUDED_OTHER_CALLS.set(0);
        XBRZ_METADATA_BUILDS.set(0);
        XBRZ_PROGRAM_BINDS.set(0);
        XBRZ_ACTIVE_BINDS.set(0);
        XBRZ_FALLBACK_BINDS.set(0);
        XBRZ_FALLBACK_PATCH.set(0);
        XBRZ_FALLBACK_META.set(0);
        XBRZ_FALLBACK_UNIT.set(0);
        SmoothBlocksXbrzGpuBridge.resetDiagnostics();
        lastPipeline = "none";
        lastTextureName = "none";
        lastTextureLabel = "none";
        lastOriginalFilter = "none";
        lastAppliedFilter = "none";
        lastSodiumOriginalFilter = "none";
        lastSodiumAppliedFilter = "none";
        lastXbrzTextureUnit = -1;
        lastXbrzBindStatus = "none";
        lastEntityXbrzStatus = "none";
        minecraft.gui.setOverlayMessage(Component.literal("SmoothBlocks diagnostics reset"), true);
    }

    public static String debugText() {
        String bind = lastXbrzTextureUnit >= 0 ? "u" + lastXbrzTextureUnit : lastXbrzBindStatus;
        return String.format(Locale.ROOT,
                "mode=%s dbg=%s T%d/%d E%d/%d\n" +
                "pass x%d fb%d p/m/u=%d/%d/%d %s %s\n" +
                "meta %s d%.2f e%.1f uv%d ov%d up%d a%d\n" +
                "iris %s ent=%d/%d %s",
                enabled ? selectedMode : "OFF", debugView,
                DEFAULT_TERRAIN_SHADER_PATCHES.get(), IRIS_TERRAIN_SHADER_PATCHES.get(),
                DEFAULT_ENTITY_SHADER_PATCHES.get(), IRIS_ENTITY_SHADER_PATCHES.get(),
                XBRZ_ACTIVE_BINDS.get(), XBRZ_FALLBACK_BINDS.get(),
                XBRZ_FALLBACK_PATCH.get(), XBRZ_FALLBACK_META.get(), XBRZ_FALLBACK_UNIT.get(),
                bind, SmoothBlocksXbrzGpuBridge.bindingStatsString(),
                SmoothBlocksXbrzMetadata.sourceMode(), SmoothBlocksXbrzMetadata.sourceMismatchPercent(),
                SmoothBlocksXbrzMetadata.edgePercent(), SmoothBlocksXbrzMetadata.uvMismatchSprites(),
                SmoothBlocksXbrzMetadata.overlappingTexels(), SmoothBlocksXbrzMetadata.uploadMismatchedBytes(),
                SmoothBlocksXbrzMetadata.animatedSprites(),
                SmoothBlocksShaderPatch.terrainStatsString(),
                ENTITY_XBRZ_ACTIVE_SETUPS.get(), ENTITY_XBRZ_PROGRAM_SETUPS.get(), SmoothBlocksTextureMetadata.stats());
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("absent");
    }

    private static void logEnvironment() {
        System.out.println("[SmoothBlocks] Loaded. Minecraft=" + modVersion("minecraft")
                + ", Iris=" + modVersion("iris") + ", Sodium=" + modVersion("sodium"));
        System.out.println("[SmoothBlocks] P=NEAREST/LINEAR/XBRZ | F6=PATH/EDGES/OFF | F7=compact debug | F8=ON/OFF | F9=reset");
    }
}
