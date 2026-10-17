package de.oai.smoothblocks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source rewriting stays separate from the shared GLSL reconstruction kernel. */
public final class SmoothBlocksShaderPatch {
    private static final String TERRAIN_MARKER = "SMOOTHBLOCKS_XBRZ_DIRECT_V13";
    private static final String TERRAIN_CALL = "smoothblocks_TerrainSample";
    private static final String TERRAIN_GRAD_CALL = "smoothblocks_TerrainSampleGrad";
    private static final String TERRAIN_LOD_CALL = "smoothblocks_TerrainSampleLod";
    private static final String ENTITY_MARKER = "SMOOTHBLOCKS_ENTITY_XBRZ_V9";
    private static final String ENTITY_CALL = "smoothblocks_EntitySample";

    private static final List<String> DIFFUSE_NAMES = List.of(
            "gtexture", "Sampler0", "u_BlockTex", "albedo", "diffuse", "diffuseTex", "texture", "tex");
    private static final List<String> AUXILIARY_NAMES = List.of(
            "light", "normal", "spec", "shadow", "noise", "depth", "overlay", "colortex", "gaux");
    private static final AtomicLong TERRAIN_BASIC_REWRITES = new AtomicLong();
    private static final AtomicLong TERRAIN_GRAD_REWRITES = new AtomicLong();
    private static final AtomicLong TERRAIN_LOD_REWRITES = new AtomicLong();
    private static final AtomicLong TERRAIN_AUXILIARY_READS = new AtomicLong();
    private static final AtomicLong TERRAIN_RAW_FETCHES = new AtomicLong();
    private static volatile String lastTerrainSampler = "none";

    private static final String PARAMETER_NAME = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String SAMPLER_PARAMETER = "\\s*sampler2D\\s+" + PARAMETER_NAME;
    private static final String UV_PARAMETER = "\\s*,\\s*vec2\\s+" + PARAMETER_NAME;
    private static final Pattern SIX_ARG_SAMPLE_NEAREST = Pattern.compile(
            "\\bvec4\\s+sampleNearest\\s*\\(" + SAMPLER_PARAMETER + UV_PARAMETER.repeat(5) + "\\s*\\)");
    private static final Pattern THREE_ARG_SAMPLE_RGSS = Pattern.compile(
            "\\bvec4\\s+sampleRGSS\\s*\\(" + SAMPLER_PARAMETER + UV_PARAMETER.repeat(2) + "\\s*\\)");
    private static final Pattern UNIFORM_SAMPLER_2D = Pattern.compile(
            "\\buniform\\s+(?:(?:lowp|mediump|highp|readonly|writeonly|coherent|volatile|restrict)\\s+)*"
                    + "sampler2D\\s+(" + PARAMETER_NAME + ")");
    private static final Pattern MAIN_FUNCTION = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern VERSION = Pattern.compile("(?m)^[\\t ]*#version\\b");

    private SmoothBlocksShaderPatch() {}

    /** Sodium imports must already be expanded; original helpers remain available to fallback modes. */
    public static String patchSodiumFragment(String source) {
        if (source == null || source.contains(TERRAIN_MARKER)) return source;
        String patched = wrapFunction(source, SIX_ARG_SAMPLE_NEAREST, "sampleNearest",
                "smoothblocks_OriginalNearest", terrainShaderHelpers(), sodiumWrapperFunction());
        if (patched.equals(source)) return source;
        return wrapFunction(patched, THREE_ARG_SAMPLE_RGSS, "sampleRGSS",
                "smoothblocks_OriginalRGSS", "", sodiumRgssWrapperFunction());
    }

    private static String wrapFunction(String source, Pattern signature, String name,
                                       String originalName, String helpers, String wrapper) {
        String code = SmoothBlocksShaderSyntax.codeOnly(source);
        Matcher function = signature.matcher(code);
        if (!function.find()) return source;
        int opening = code.indexOf('{', function.end());
        if (opening < 0) return source;
        int end = SmoothBlocksShaderSyntax.closingBrace(code, opening);
        if (end < 0) return source;
        String original = source.substring(function.start(), end)
                .replaceFirst("\\b" + name + "\\s*\\(", originalName + "(");
        return source.substring(0, function.start()) + helpers + "\n" + original + "\n" + wrapper + source.substring(end);
    }

    /** Run after Iris AST transforms so reconstruction cannot feed back into helper UV/material probes. */
    public static String patchIrisTerrainFragmentPostTransform(String source) {
        if (source == null || source.contains(TERRAIN_MARKER)) return source;
        String patched = rewriteTerrainMainSamples(source);
        if (patched.equals(source)) return source;
        return injectAfterHeader(patched, terrainShaderHelpers() + "\n" + terrainDirectSamplerFunctions());
    }

    public static String markIrisEntitySamples(String shaderName, String source) {
        if (!isEntityShaderName(shaderName) || source == null || source.contains(ENTITY_MARKER)
                || source.contains(ENTITY_CALL + "(")) return source;
        return rewriteDiffuseSamples(source);
    }

    /** Entity calls are marked before Iris transforms; helpers are inserted afterward. */
    public static String finishIrisEntityFragment(String source) {
        if (source == null || source.contains(ENTITY_MARKER) || !source.contains(ENTITY_CALL + "(")) return source;
        return injectAfterHeader(source, entityShaderHelpers());
    }

    public static String patchVanillaEntityFragmentDirect(String shaderName, String source) {
        if (!isEntityShaderName(shaderName) || source == null || source.contains(ENTITY_MARKER)) return source;
        String patched = rewriteDiffuseSamples(source);
        return patched.equals(source) ? source : injectAfterHeader(patched, entityShaderHelpers());
    }

    public static boolean isEntityShaderName(String shaderName) {
        if (shaderName == null) return false;
        String name = shaderName.toLowerCase(Locale.ROOT);
        if (name.contains("gui") || name.contains("text") || name.contains("particle")) return false;
        return name.contains("entit") || name.contains("armor") || name.contains("eyes")
                || name.contains("hand") || name.contains("item") || name.contains("energy_swirl") || name.contains("breeze_wind");
    }

    private enum CallKind {
        BASIC(TERRAIN_CALL, TERRAIN_BASIC_REWRITES),
        GRAD(TERRAIN_GRAD_CALL, TERRAIN_GRAD_REWRITES),
        LOD(TERRAIN_LOD_CALL, TERRAIN_LOD_REWRITES);

        final String replacement;
        final AtomicLong count;

        CallKind(String replacement, AtomicLong count) {
            this.replacement = replacement;
            this.count = count;
        }
    }

    private record CallSite(int start, int end, CallKind kind) {}

    private static String rewriteTerrainMainSamples(String source) {
        String code = SmoothBlocksShaderSyntax.codeOnly(source);
        String sampler = diffuseSampler(code);
        if (sampler == null) return source;
        lastTerrainSampler = sampler;
        Matcher main = MAIN_FUNCTION.matcher(code);
        if (!main.find()) return source;
        int opening = code.indexOf('{', main.end());
        if (opening < 0) return source;
        int end = SmoothBlocksShaderSyntax.closingBrace(code, opening);
        if (end < 0) return source;

        List<CallSite> calls = terrainCalls(code.substring(opening, end), sampler);
        StringBuilder patched = new StringBuilder(source);
        // Descending replacements preserve the offsets found in the masked source.
        for (int i = calls.size() - 1; i >= 0; i--) {
            CallSite call = calls.get(i);
            patched.replace(opening + call.start, opening + call.end, call.kind.replacement);
            call.kind.count.incrementAndGet();
        }
        String output = patched.toString();
        String remaining = SmoothBlocksShaderSyntax.codeOnly(output);
        TERRAIN_AUXILIARY_READS.addAndGet(terrainCalls(remaining, sampler).size());
        Pattern raw = Pattern.compile("\\btexelFetch[A-Za-z0-9_]*\\s*\\(\\s*" + Pattern.quote(sampler) + "\\s*,");
        TERRAIN_RAW_FETCHES.addAndGet(raw.matcher(remaining).results().count());
        return output;
    }

    private static List<CallSite> terrainCalls(String code, String sampler) {
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])"
                + "(texture2DGradARB|texture2DGrad|textureGrad|texture2DLodARB|texture2DLod|textureLod|texture2D|texture)"
                + "\\s*\\(\\s*" + Pattern.quote(sampler) + "\\s*,");
        Matcher call = pattern.matcher(code);
        List<CallSite> calls = new ArrayList<>();
        while (call.find()) {
            String name = call.group(1);
            CallKind kind = name.contains("Grad") ? CallKind.GRAD : name.contains("Lod") ? CallKind.LOD : CallKind.BASIC;
            calls.add(new CallSite(call.start(1), call.end(1), kind));
        }
        return calls;
    }

    private static String rewriteDiffuseSamples(String source) {
        String code = SmoothBlocksShaderSyntax.codeOnly(source);
        String sampler = diffuseSampler(code);
        if (sampler == null) return source;
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])(texture2D|texture)\\s*\\(\\s*"
                + Pattern.quote(sampler) + "\\s*,");
        Matcher call = pattern.matcher(code);
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (call.find()) {
            starts.add(call.start(1));
            ends.add(call.end(1));
        }
        StringBuilder patched = new StringBuilder(source);
        for (int i = starts.size() - 1; i >= 0; i--) patched.replace(starts.get(i), ends.get(i), ENTITY_CALL);
        return patched.toString();
    }

    /** Preferred names precede a conservative auxiliary-map exclusion heuristic. */
    private static String diffuseSampler(String code) {
        Set<String> declared = new LinkedHashSet<>();
        Matcher uniform = UNIFORM_SAMPLER_2D.matcher(code);
        while (uniform.find()) declared.add(uniform.group(1));
        for (String name : DIFFUSE_NAMES) if (declared.contains(name)) return name;
        return declared.stream().filter(name -> !auxiliarySampler(name)).findFirst().orElse(null);
    }

    private static boolean auxiliarySampler(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("sampler1") || lower.equals("sampler2")
                || AUXILIARY_NAMES.stream().anyMatch(lower::contains);
    }

    private static String injectAfterHeader(String source, String helpers) {
        String code = SmoothBlocksShaderSyntax.codeOnly(source);
        Matcher version = VERSION.matcher(code);
        if (!version.find()) {
            Matcher main = MAIN_FUNCTION.matcher(code);
            return main.find() ? insert(source, main.start(), helpers) : source;
        }
        int cursor = nextLine(code, version.end());
        while (cursor < code.length()) {
            int end = nextLine(code, cursor);
            String line = code.substring(cursor, end).trim();
            if (!line.isEmpty() && !line.startsWith("#extension") && !line.startsWith("#pragma")) {
                // A block comment may end midway through this line; insert before the first actual token.
                while (cursor < end && Character.isWhitespace(code.charAt(cursor))) cursor++;
                break;
            }
            cursor = end;
        }
        return insert(source, cursor, helpers);
    }

    private static int nextLine(String source, int from) {
        int newline = source.indexOf('\n', from);
        return newline < 0 ? source.length() : newline + 1;
    }

    private static String insert(String source, int offset, String helpers) {
        return source.substring(0, offset) + "\n" + helpers + "\n" + source.substring(offset);
    }

    public static String terrainStatsString() {
        return "s=" + lastTerrainSampler
                + " main=" + TERRAIN_BASIC_REWRITES.get() + "/" + TERRAIN_GRAD_REWRITES.get() + "/" + TERRAIN_LOD_REWRITES.get()
                + " aux=" + TERRAIN_AUXILIARY_READS.get() + "/" + TERRAIN_RAW_FETCHES.get();
    }

    /** Shared reconstruction kernel; Java packing and GLSL decoding must use the same states and descriptor layout. */
    private static String terrainShaderHelpers() {
        String shader = """
                /* SMOOTHBLOCKS_XBRZ_DIRECT_V13
                 * xBRZ Freescale: expensive edge classification is precomputed on CPU.
                 * This shader reconstructs directly at the projected screen resolution.
                 */
                uniform usampler2D smoothblocks_Meta;
                uniform int smoothblocks_Mode;
                uniform int smoothblocks_Debug;

                const uint smoothblocks_META_MISSING = SB_MISSINGu;
                const uint smoothblocks_META_ANIMATED = SB_ANIMATEDu;
                const float smoothblocks_SQRT_HALF = 0.7071067811865476;

                float smoothblocks_DistVisible(vec4 a, vec4 b) {
                    // Match CPU distance: lower-alpha-weighted YCbCr plus alpha difference.
                    const vec3 w = vec3(0.2627, 0.6780, 0.0593);
                    const float scaleB = 0.5 / (1.0 - w.b);
                    const float scaleR = 0.5 / (1.0 - w.r);
                    vec3 diff = a.rgb - b.rgb;
                    float y = dot(diff, w);
                    float cb = scaleB * (diff.b - y);
                    float cr = scaleR * (diff.r - y);
                    float rgbDistance = sqrt(y * y + cb * cb + cr * cr);
                    return min(a.a, b.a) * rgbDistance + abs(a.a - b.a);
                }

                float smoothblocks_LeftRatio(vec2 center, vec2 origin, vec2 direction, vec2 scale) {
                    // Signed distance to the corner's blend line, scaled into screen pixels.
                    vec2 p0 = center - origin;
                    vec2 proj = direction * (dot(p0, direction) / dot(direction, direction));
                    vec2 distv = p0 - proj;
                    vec2 orth = vec2(-direction.y, direction.x);
                    float side = sign(dot(p0, orth));
                    float v = side * length(distv * scale);
                    return smoothstep(-smoothblocks_SQRT_HALF, smoothblocks_SQRT_HALF, v);
                }

                vec2 smoothblocks_LocalScale(vec2 pixelSize, vec2 du, vec2 dv) {
                    // Per-axis derivative magnitudes map texels to screen pixels; epsilon guards degenerate UVs.
                    vec2 texelScreenSize = sqrt(du * du + dv * dv);
                    return pixelSize / max(texelScreenSize, vec2(1e-12));
                }

                uint smoothblocks_State(uint meta, uint shift) {
                    return (meta >> shift) & 7u;
                }

                bool smoothblocks_Shallow(uint state) {
                    return state == 3u || state == 5u;
                }

                bool smoothblocks_Steep(uint state) {
                    return state == 4u || state == 5u;
                }

                bool smoothblocks_Line(uint state) {
                    return state >= 2u && state <= 5u;
                }

                uint smoothblocks_RawMeta(ivec2 texel) {
                    uvec4 raw = texelFetch(smoothblocks_Meta, texel, 0);
                    return raw.r | (raw.g << 8u);
                }

                // Extra indices begin below the source atlas and count 16-bit cells, not bytes.
                uint smoothblocks_ExtraMeta(uint index, ivec2 sourceSize) {
                    return smoothblocks_RawMeta(ivec2(int(index % uint(sourceSize.x)),
                        sourceSize.y + int(index / uint(sourceSize.x))));
                }

                uint smoothblocks_LoadMeta(ivec2 texel, ivec2 sourceSize, out ivec4 wrapBounds) {
                    wrapBounds = ivec4(0);
                    uint meta = smoothblocks_RawMeta(texel);
                    // State 7 is unused by xBRZ; its remaining bits address a sprite descriptor.
                    if ((meta & 7u) != 7u || meta == smoothblocks_META_ANIMATED) return meta;
                    uint descriptor = (meta >> SB_MARKER_SHIFTu) * SB_DESCRIPTOR_CELLSu;
                    ivec2 origin = ivec2(smoothblocks_ExtraMeta(descriptor, sourceSize),
                                         smoothblocks_ExtraMeta(descriptor + 1u, sourceSize));
                    uint widthFlags = smoothblocks_ExtraMeta(descriptor + 2u, sourceSize);
                    uint width = widthFlags & SB_WIDTH_MASKu;
                    if ((widthFlags & SB_WRAP_FLAGu) != 0u)
                        wrapBounds = ivec4(origin, int(width), int(smoothblocks_ExtraMeta(descriptor + 3u, sourceSize)));
                    uint address = smoothblocks_ExtraMeta(descriptor + SB_ADDRESS_CELLu, sourceSize)
                        | (smoothblocks_ExtraMeta(descriptor + (SB_ADDRESS_CELLu + 1u), sourceSize) << 16u);
                    ivec2 local = texel - origin;
                    return smoothblocks_ExtraMeta(address + uint(local.y) * width + uint(local.x), sourceSize);
                }

                // Effects need explicit bilinear taps because the backing sampler stays nearest.
                // Premultiplied mixing avoids hidden-RGB fringes; atlas padding supplies border taps.
                vec4 smoothblocks_AnimatedLinear(sampler2D source, vec2 uv) {
                    ivec2 size = textureSize(source, 0);
                    vec2 tc = uv * vec2(size) - vec2(0.5);
                    ivec2 p0 = ivec2(floor(tc));
                    vec2 f = fract(tc);
                    ivec2 hi = size - ivec2(1);
                    ivec2 p00 = clamp(p0,                 ivec2(0), hi);
                    ivec2 p10 = clamp(p0 + ivec2(1, 0), ivec2(0), hi);
                    ivec2 p01 = clamp(p0 + ivec2(0, 1), ivec2(0), hi);
                    ivec2 p11 = clamp(p0 + ivec2(1, 1), ivec2(0), hi);
                    vec4 c00 = texelFetch(source, p00, 0);
                    vec4 c10 = texelFetch(source, p10, 0);
                    vec4 c01 = texelFetch(source, p01, 0);
                    vec4 c11 = texelFetch(source, p11, 0);
                    vec4 q00 = vec4(c00.rgb * c00.a, c00.a);
                    vec4 q10 = vec4(c10.rgb * c10.a, c10.a);
                    vec4 q01 = vec4(c01.rgb * c01.a, c01.a);
                    vec4 q11 = vec4(c11.rgb * c11.a, c11.a);
                    vec4 q = mix(mix(q00, q10, f.x), mix(q01, q11, f.x), f.y);
                    return q.a > 1e-6 ? vec4(q.rgb / q.a, q.a) : vec4(0.0);
                }

                vec4 smoothblocks_Fetch(sampler2D source, ivec2 center, ivec2 offset, uint meta, ivec4 wrapBounds) {
                    // Periodic descriptors wrap inside their own sprite; hard bits clamp isolated sprites.
                    if (wrapBounds.z > 0) {
                        ivec2 p = center + offset - wrapBounds.xy;
                        if (p.x < 0) p.x += wrapBounds.z;
                        else if (p.x >= wrapBounds.z) p.x -= wrapBounds.z;
                        if (p.y < 0) p.y += wrapBounds.w;
                        else if (p.y >= wrapBounds.w) p.y -= wrapBounds.w;
                        return texelFetch(source, wrapBounds.xy + p, 0);
                    }
                    if (offset.x < 0 && (meta & (1u << 12u)) != 0u) offset.x = 0;
                    if (offset.x > 0 && (meta & (1u << 13u)) != 0u) offset.x = 0;
                    if (offset.y < 0 && (meta & (1u << 14u)) != 0u) offset.y = 0;
                    if (offset.y > 0 && (meta & (1u << 15u)) != 0u) offset.y = 0;
                    return texelFetch(source, center + offset, 0);
                }

                vec4 smoothblocks_Closer(vec4 center, vec4 a, vec4 b) {
                    float da = smoothblocks_DistVisible(center, a);
                    float db = smoothblocks_DistVisible(center, b);
                    // Equality favors b, preserving the reference corner ordering.
                    return db <= da ? b : a;
                }

                void smoothblocks_MixVisible(inout vec3 premul, inout float alpha, vec4 target, float t) {
                    t = clamp(t, 0.0, 1.0);
                    premul = mix(premul, target.rgb * target.a, t);
                    alpha = mix(alpha, target.a, t);
                }

                vec4 smoothblocks_XbrzDirect(sampler2D source, vec2 uv, vec2 scale) {
                    ivec2 sourceSize = textureSize(source, 0);
                    vec2 texelCoord = uv * vec2(sourceSize);
                    ivec2 centerTexel = clamp(ivec2(floor(texelCoord)), ivec2(0), sourceSize - ivec2(1));

                    ivec2 metaSize = textureSize(smoothblocks_Meta, 0);
                    if (sourceSize.x != metaSize.x || sourceSize.y > metaSize.y) {
                        // Debug PATH/EDGES: red = atlas/meta size mismatch.
                        if (smoothblocks_Debug != 0) return vec4(1.0, 0.0, 0.0, 1.0);
                        return texelFetch(source, centerTexel, 0);
                    }

                    ivec4 wrapBounds;
                    uint meta = smoothblocks_LoadMeta(centerTexel, sourceSize, wrapBounds);
                    if (meta == smoothblocks_META_ANIMATED) {
                        // Yellow = intentional animated-only bilinear path.
                        if (smoothblocks_Debug != 0) return vec4(1.0, 1.0, 0.0, 1.0);
                        return smoothblocks_AnimatedLinear(source, uv);
                    }
                    if (meta == smoothblocks_META_MISSING) {
                        // Magenta = missing/padding metadata. This path is NEAREST, never LINEAR.
                        if (smoothblocks_Debug != 0) return vec4(1.0, 0.0, 1.0, 1.0);
                        return texelFetch(source, centerTexel, 0);
                    }

                    if (smoothblocks_Debug == 1) {
                        // PATH: green means this fragment is a valid static xBRZ texel.
                        return vec4(0.0, 1.0, 0.0, 1.0);
                    }

                    vec2 pos = fract(texelCoord) - vec2(0.5);
                    vec4 E = smoothblocks_Fetch(source, centerTexel, ivec2( 0, 0), meta, wrapBounds);
                    vec4 B = smoothblocks_Fetch(source, centerTexel, ivec2( 0,-1), meta, wrapBounds);
                    vec4 D = smoothblocks_Fetch(source, centerTexel, ivec2(-1, 0), meta, wrapBounds);
                    vec4 F = smoothblocks_Fetch(source, centerTexel, ivec2( 1, 0), meta, wrapBounds);
                    vec4 H = smoothblocks_Fetch(source, centerTexel, ivec2( 0, 1), meta, wrapBounds);

                    // Metadata corner mapping: TL bits 0..2, TR 3..5, BR 6..8, BL 9..11.
                    uint tl = smoothblocks_State(meta, 0u);
                    uint tr = smoothblocks_State(meta, 3u);
                    uint br = smoothblocks_State(meta, 6u);
                    uint bl = smoothblocks_State(meta, 9u);

                    if (smoothblocks_Debug == 2) {
                        // EDGES: cyan = classified edge; dark gray = valid metadata without an edge.
                        bool hasEdge = (tl | tr | br | bl) != 0u;
                        return hasEdge ? vec4(0.0, 1.0, 1.0, 1.0) : vec4(0.08, 0.08, 0.08, 1.0);
                    }

                    // Start from E; compose corners in reference order BR, BL, TR, TL. Order affects overlapping blends.
                    vec3 resPremul = E.rgb * E.a;
                    float resAlpha = E.a;

                    if (br > 0u && br <= 5u) {
                        vec2 origin = vec2(0.0, smoothblocks_SQRT_HALF);
                        vec2 direction = vec2(1.0, -1.0);
                        if (smoothblocks_Line(br)) {
                            bool shallow = smoothblocks_Shallow(br);
                            bool steep = smoothblocks_Steep(br);
                            origin = shallow ? vec2(0.0, 0.25) : vec2(0.0, 0.5);
                            direction.x += shallow ? 1.0 : 0.0;
                            direction.y -= steep ? 1.0 : 0.0;
                        }
                        vec4 blendPix = smoothblocks_Closer(E, H, F);
                        smoothblocks_MixVisible(resPremul, resAlpha, blendPix,
                                smoothblocks_LeftRatio(pos, origin, direction, scale));
                    }

                    if (bl > 0u && bl <= 5u) {
                        vec2 origin = vec2(-smoothblocks_SQRT_HALF, 0.0);
                        vec2 direction = vec2(1.0, 1.0);
                        if (smoothblocks_Line(bl)) {
                            bool shallow = smoothblocks_Shallow(bl);
                            bool steep = smoothblocks_Steep(bl);
                            origin = shallow ? vec2(-0.25, 0.0) : vec2(-0.5, 0.0);
                            direction.y += shallow ? 1.0 : 0.0;
                            direction.x += steep ? 1.0 : 0.0;
                        }
                        vec4 blendPix = smoothblocks_Closer(E, H, D);
                        smoothblocks_MixVisible(resPremul, resAlpha, blendPix,
                                smoothblocks_LeftRatio(pos, origin, direction, scale));
                    }

                    if (tr > 0u && tr <= 5u) {
                        vec2 origin = vec2(smoothblocks_SQRT_HALF, 0.0);
                        vec2 direction = vec2(-1.0, -1.0);
                        if (smoothblocks_Line(tr)) {
                            bool shallow = smoothblocks_Shallow(tr);
                            bool steep = smoothblocks_Steep(tr);
                            origin = shallow ? vec2(0.25, 0.0) : vec2(0.5, 0.0);
                            direction.y -= shallow ? 1.0 : 0.0;
                            direction.x -= steep ? 1.0 : 0.0;
                        }
                        vec4 blendPix = smoothblocks_Closer(E, F, B);
                        smoothblocks_MixVisible(resPremul, resAlpha, blendPix,
                                smoothblocks_LeftRatio(pos, origin, direction, scale));
                    }

                    if (tl > 0u && tl <= 5u) {
                        vec2 origin = vec2(0.0, -smoothblocks_SQRT_HALF);
                        vec2 direction = vec2(-1.0, 1.0);
                        if (smoothblocks_Line(tl)) {
                            bool shallow = smoothblocks_Shallow(tl);
                            bool steep = smoothblocks_Steep(tl);
                            origin = shallow ? vec2(0.0, -0.25) : vec2(0.0, -0.5);
                            direction.x -= shallow ? 1.0 : 0.0;
                            direction.y += steep ? 1.0 : 0.0;
                        }
                        vec4 blendPix = smoothblocks_Closer(E, D, B);
                        smoothblocks_MixVisible(resPremul, resAlpha, blendPix,
                                smoothblocks_LeftRatio(pos, origin, direction, scale));
                    }

                    // Return straight alpha; guard division at transparent silhouettes.
                    vec3 outRgb = resAlpha > 1e-6 ? resPremul / resAlpha : E.rgb;
                    return vec4(clamp(outRgb, 0.0, 1.0), clamp(resAlpha, 0.0, 1.0));
                }
                """;
        return shader
                .replace("SB_MISSING", Integer.toString(SmoothBlocksMetadataFormat.MISSING))
                .replace("SB_ANIMATED", Integer.toString(SmoothBlocksMetadataFormat.ANIMATED_LINEAR))
                .replace("SB_MARKER_SHIFT", Integer.toString(SmoothBlocksMetadataFormat.MARKER_SHIFT))
                .replace("SB_DESCRIPTOR_CELLS", Integer.toString(SmoothBlocksMetadataFormat.DESCRIPTOR_CELLS))
                .replace("SB_WIDTH_MASK", Integer.toString(SmoothBlocksMetadataFormat.WIDTH_MASK))
                .replace("SB_WRAP_FLAG", Integer.toString(SmoothBlocksMetadataFormat.WRAP_FLAG))
                .replace("SB_ADDRESS_CELL", Integer.toString(SmoothBlocksMetadataFormat.ADDRESS_CELL));
    }

    /** xBRZ always reconstructs level 0; bias/LOD affect only the original sampling fallback. */
    private static String terrainDirectSamplerFunctions() {
        return """
                vec4 smoothblocks_TerrainSample(sampler2D source, vec2 uv) {
                    vec2 pixelSize = 1.0 / vec2(textureSize(source, 0));
                    vec2 du = dFdx(uv);
                    vec2 dv = dFdy(uv);
                    if (smoothblocks_Mode == 2) {
                        return smoothblocks_XbrzDirect(source, uv, smoothblocks_LocalScale(pixelSize, du, dv));
                    }
                    return textureGrad(source, uv, du, dv);
                }

                vec4 smoothblocks_TerrainSample(sampler2D source, vec2 uv, float bias) {
                    if (smoothblocks_Mode == 2) {
                        vec2 pixelSize = 1.0 / vec2(textureSize(source, 0));
                        vec2 du = dFdx(uv);
                        vec2 dv = dFdy(uv);
                        return smoothblocks_XbrzDirect(source, uv, smoothblocks_LocalScale(pixelSize, du, dv));
                    }
                    return texture(source, uv, bias);
                }

                vec4 smoothblocks_TerrainSampleGrad(sampler2D source, vec2 uv, vec2 du, vec2 dv) {
                    if (smoothblocks_Mode == 2) {
                        vec2 pixelSize = 1.0 / vec2(textureSize(source, 0));
                        return smoothblocks_XbrzDirect(source, uv, smoothblocks_LocalScale(pixelSize, du, dv));
                    }
                    return textureGrad(source, uv, du, dv);
                }

                vec4 smoothblocks_TerrainSampleLod(sampler2D source, vec2 uv, float lod) {
                    if (smoothblocks_Mode == 2) {
                        vec2 pixelSize = 1.0 / vec2(textureSize(source, 0));
                        vec2 du = dFdx(uv);
                        vec2 dv = dFdy(uv);
                        return smoothblocks_XbrzDirect(source, uv, smoothblocks_LocalScale(pixelSize, du, dv));
                    }
                    return textureLod(source, uv, lod);
                }
                """;
    }

    private static String sodiumWrapperFunction() {
        return """
                vec4 sampleNearest(sampler2D sampler, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
                    if (smoothblocks_Mode == 2) {
                        return smoothblocks_XbrzDirect(sampler, uv, smoothblocks_LocalScale(pixelSize, du, dv));
                    }
                    if (smoothblocks_Mode == 1) {
                        return textureGrad(sampler, uv, du, dv);
                    }
                    return smoothblocks_OriginalNearest(sampler, uv, pixelSize, du, dv, texelScreenSize);
                }
                """;
    }
    private static String sodiumRgssWrapperFunction() {
        return """
                vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
                    if (smoothblocks_Mode == 2) {
                        vec2 du = dFdx(uv);
                        vec2 dv = dFdy(uv);
                        return smoothblocks_XbrzDirect(source, uv, smoothblocks_LocalScale(pixelSize, du, dv));
                    }
                    if (smoothblocks_Mode == 1) {
                        return textureGrad(source, uv, dFdx(uv), dFdy(uv));
                    }
                    return smoothblocks_OriginalRGSS(source, uv, pixelSize);
                }
                """;
    }

    /** Reuse the terrain kernel under an entity uniform namespace so reconstruction stays identical. */
    private static String entityShaderHelpers() {
        String shared = terrainShaderHelpers()
                .replace(TERRAIN_MARKER, ENTITY_MARKER)
                .replace("smoothblocks_", "smoothblocks_Entity");
        return shared + """
                vec4 smoothblocks_EntityReconstruct(sampler2D source, vec2 uv) {
                    vec2 pixelSize = 1.0 / vec2(textureSize(source, 0));
                    return smoothblocks_EntityXbrzDirect(source, uv,
                            smoothblocks_EntityLocalScale(pixelSize, dFdx(uv), dFdy(uv)));
                }

                vec4 smoothblocks_EntitySample(sampler2D source, vec2 uv) {
                    if (smoothblocks_EntityMode == 2) return smoothblocks_EntityReconstruct(source, uv);
                    return texture(source, uv);
                }

                vec4 smoothblocks_EntitySample(sampler2D source, vec2 uv, float bias) {
                    if (smoothblocks_EntityMode == 2) return smoothblocks_EntityReconstruct(source, uv);
                    return texture(source, uv, bias);
                }
                """;
    }
}
