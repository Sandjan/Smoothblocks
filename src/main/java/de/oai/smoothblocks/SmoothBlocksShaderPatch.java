package de.oai.smoothblocks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

/** Shader-source patches for direct terrain and entity xBRZ reconstruction. */
public final class SmoothBlocksShaderPatch {
    private SmoothBlocksShaderPatch() {}

    private static final String TERRAIN_MARKER = "SMOOTHBLOCKS_XBRZ_DIRECT_V13";
    private static final String TERRAIN_CALL = "smoothblocks_TerrainSample";
    private static final String TERRAIN_GRAD_CALL = "smoothblocks_TerrainSampleGrad";
    private static final String TERRAIN_LOD_CALL = "smoothblocks_TerrainSampleLod";
    private static final String ENTITY_MARKER = "SMOOTHBLOCKS_ENTITY_XBRZ_V9";
    private static final String ENTITY_CALL = "smoothblocks_EntitySample";

    // Compile-time Iris coverage diagnostics. These are intentionally aggregate counters
    // so F7 stays within one screenshot instead of dumping shader sources.
    private static final AtomicLong TERRAIN_BASIC_REWRITES = new AtomicLong();
    private static final AtomicLong TERRAIN_GRAD_REWRITES = new AtomicLong();
    private static final AtomicLong TERRAIN_LOD_REWRITES = new AtomicLong();
    private static final AtomicLong TERRAIN_FILTERED_LEAKS = new AtomicLong();
    private static final AtomicLong TERRAIN_RAW_FETCHES = new AtomicLong();
    private static volatile String lastTerrainSampler = "none";

    private static final Pattern SIX_ARG_SAMPLE_NEAREST = Pattern.compile(
            "\\bvec4\\s+sampleNearest\\s*\\("
                    + "\\s*sampler2D\\s+[A-Za-z_][A-Za-z0-9_]*\\s*,"
                    + "\\s*vec2\\s+[A-Za-z_][A-Za-z0-9_]*\\s*,"
                    + "\\s*vec2\\s+[A-Za-z_][A-Za-z0-9_]*\\s*,"
                    + "\\s*vec2\\s+[A-Za-z_][A-Za-z0-9_]*\\s*,"
                    + "\\s*vec2\\s+[A-Za-z_][A-Za-z0-9_]*\\s*,"
                    + "\\s*vec2\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\)"
    );

    private static final Pattern THREE_ARG_SAMPLE_RGSS = Pattern.compile(
            "\\bvec4\\s+sampleRGSS\\s*\\("
                    + "\\s*sampler2D\\s+[A-Za-z_][A-Za-z0-9_]*\\s*,"
                    + "\\s*vec2\\s+[A-Za-z_][A-Za-z0-9_]*\\s*,"
                    + "\\s*vec2\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\)"
    );

    private static final Pattern UNIFORM_SAMPLER_2D = Pattern.compile(
            "\\buniform\\s+(?:(?:lowp|mediump|highp|readonly|writeonly|coherent|volatile|restrict)\\s+)*"
                    + "sampler2D\\s+([A-Za-z_][A-Za-z0-9_]*)"
    );

    private static final Pattern MAIN_FUNCTION = Pattern.compile("\\bvoid\\s+main\\s*\\(");

    /** Default Sodium path: replace Sodium's own helper after imports have been resolved. */
    public static String patchSodiumFragment(String source) {
        if (source == null || source.contains(TERRAIN_MARKER)) return source;

        Matcher matcher = SIX_ARG_SAMPLE_NEAREST.matcher(source);
        if (!matcher.find()) return source;

        int start = matcher.start();
        int brace = source.indexOf('{', matcher.end());
        if (brace < 0) return source;
        int end = findMatchingBrace(source, brace);
        if (end < 0) return source;

        String originalFunction = source.substring(start, end);
        String renamedOriginal = originalFunction.replaceFirst(
                "vec4\\s+sampleNearest\\s*\\(",
                "vec4 smoothblocks_OriginalNearest("
        );

        String injected = terrainShaderHelpers() + "\n" + renamedOriginal + "\n" + sodiumWrapperFunction();
        String patched = source.substring(0, start) + injected + source.substring(end);
        return patchSodiumRgss(patched);
    }

    /**
     * Sodium can route terrain through sampleRGSS(), which mixes sampleNearest() with
     * textureLod(). If left untouched, XBRZ mode is therefore blended with RGSS on
     * parts of the same static sprite. XBRZ must bypass RGSS completely.
     */
    private static String patchSodiumRgss(String source) {
        Matcher matcher = THREE_ARG_SAMPLE_RGSS.matcher(source);
        if (!matcher.find()) return source;

        int start = matcher.start();
        int brace = source.indexOf('{', matcher.end());
        if (brace < 0) return source;
        int end = findMatchingBrace(source, brace);
        if (end < 0) return source;

        String originalFunction = source.substring(start, end);
        String renamedOriginal = originalFunction.replaceFirst(
                "vec4\\s+sampleRGSS\\s*\\(",
                "vec4 smoothblocks_OriginalRGSS("
        );
        return source.substring(0, start) + renamedOriginal + "\n"
                + sodiumRgssWrapperFunction() + source.substring(end);
    }

    /**
     * Iris terrain must be patched only AFTER Iris/Sodium finished transforming the
     * shaderpack source.  Earlier versions rewrote texture calls before Iris' AST
     * pass; that let our custom call participate in transformations intended for the
     * shaderpack's original sampling graph and made auxiliary atlas reads (POM,
     * generated normals, material probes) indistinguishable from the final albedo
     * read.  The validated xBRZ filter belongs only on final, direct diffuse reads.
     */
    public static String patchIrisTerrainFragmentPostTransform(String source) {
        if (source == null || source.contains(TERRAIN_MARKER)) return source;
        String marked = rewriteTerrainMainSamples(source);
        if (marked.equals(source)) return source;
        return injectAfterHeader(marked, terrainShaderHelpers() + "\n" + terrainDirectSamplerFunctions());
    }

    /** Kept for binary/source compatibility with older local harnesses; no pre-AST rewrite. */
    public static String markIrisTerrainSamples(String source) {
        return source;
    }

    /** Kept for binary/source compatibility; the real Iris patch is post-transform now. */
    public static String finishIrisTerrainFragment(String source) {
        return patchIrisTerrainFragmentPostTransform(source);
    }

    /** Iris phase 1 for entity/armor/hand programs. */
    public static String markIrisEntitySamples(String shaderName, String source) {
        if (!isEntityShaderName(shaderName) || source == null || source.contains(ENTITY_MARKER)
                || source.contains(ENTITY_CALL + "(")) return source;
        return rewriteDiffuseSamples(source, ENTITY_CALL);
    }

    /** Iris phase 2: inject the common p1 kernel after Iris has rewritten the source. */
    public static String finishIrisEntityFragment(String source) {
        if (source == null || source.contains(ENTITY_MARKER) || !source.contains(ENTITY_CALL + "(")) return source;
        return injectAfterHeader(source, entityShaderHelpers());
    }

    /** Vanilla/Mojang entity shader path when Iris does not transform the program. */
    public static String patchVanillaEntityFragmentDirect(String shaderName, String source) {
        if (!isEntityShaderName(shaderName) || source == null || source.contains(ENTITY_MARKER)) return source;
        String marked = rewriteDiffuseSamples(source, ENTITY_CALL);
        if (marked.equals(source)) return source;
        return injectAfterHeader(marked, entityShaderHelpers());
    }

    public static boolean isEntityShaderName(String shaderName) {
        if (shaderName == null) return false;
        String s = shaderName.toLowerCase(Locale.ROOT);
        if (s.contains("gui") || s.contains("text") || s.contains("particle")) return false;
        return s.contains("entit") || s.contains("armor") || s.contains("eyes")
                || s.contains("hand") || s.contains("item") || s.contains("energy_swirl") || s.contains("breeze_wind");
    }

    /**
     * Rewrite diffuse atlas lookups that occur directly in main() only.
     *
     * Shaderpacks often sample the same atlas in helper functions for POM, generated
     * normals, material detection or texel snapping. Replacing those reads with xBRZ
     * changes the shaderpack's coordinate calculations and can make one block sample
     * metadata from unrelated atlas sprites. That was the source of the apparently
     * "animated"/missing islands seen inside static blocks in v1.6.1 diagnostics.
     *
     * Every direct diffuse-atlas fetch in main() can be a final albedo branch and is patched.
     * Atlas reads inside helper functions are deliberately left untouched and, in XBRZ mode, see the forced NEAREST sampler
     * rather than LINEAR. Thus a static block is never a LINEAR/xBRZ mixture.
     */
    private static String rewriteTerrainMainSamples(String source) {
        Set<String> declared = new LinkedHashSet<>();
        Matcher declaration = UNIFORM_SAMPLER_2D.matcher(source);
        while (declaration.find()) declared.add(declaration.group(1));
        if (declared.isEmpty()) return source;

        String sampler = chooseDiffuseSampler(declared);
        if (sampler == null) return source;
        lastTerrainSampler = sampler;

        Matcher main = MAIN_FUNCTION.matcher(source);
        if (!main.find()) return source;
        int brace = source.indexOf('{', main.end());
        if (brace < 0) return source;
        int end = findMatchingBrace(source, brace);
        if (end < 0) return source;

        String body = source.substring(brace, end);
        List<TerrainCallSite> mainCalls = findTerrainCalls(body, sampler);
        if (mainCalls.isEmpty()) {
            TERRAIN_FILTERED_LEAKS.addAndGet(countAllFilteredCalls(source, sampler));
            TERRAIN_RAW_FETCHES.addAndGet(countRawFetches(source, sampler));
            return source;
        }

        // Patch every diffuse-atlas lookup that is directly in main(). v1.6.2 only
        // patched calls whose UV expression text matched the first sample. Shaderpacks
        // can select different final albedo coordinates per material (POM/parallax,
        // alternate material branches), which left those branches on raw NEAREST and
        // produced the remaining block-specific pixel islands. Helper-function probes
        // remain untouched so xBRZ cannot feed back into coordinate/material logic.
        StringBuilder patched = new StringBuilder(body);
        long nb = 0, ng = 0, nl = 0;
        int patchedCalls = 0;
        for (int i = mainCalls.size() - 1; i >= 0; i--) {
            TerrainCallSite call = mainCalls.get(i);
            String replacement = switch (call.kind) {
                case BASIC -> TERRAIN_CALL;
                case GRAD -> TERRAIN_GRAD_CALL;
                case LOD -> TERRAIN_LOD_CALL;
            };
            patched.replace(call.functionStart, call.functionEnd, replacement);
            patchedCalls++;
            switch (call.kind) {
                case BASIC -> nb++;
                case GRAD -> ng++;
                case LOD -> nl++;
            }
        }
        if (patchedCalls == 0) return source;

        String out = source.substring(0, brace) + patched + source.substring(end);
        TERRAIN_BASIC_REWRITES.addAndGet(nb);
        TERRAIN_GRAD_REWRITES.addAndGet(ng);
        TERRAIN_LOD_REWRITES.addAndGet(nl);

        // These are intentional non-primary atlas reads. In XBRZ mode Iris' terrain
        // sampler is forced to NEAREST, so leaving them alone cannot introduce LINEAR
        // filtering into a static block's visible albedo.
        TERRAIN_FILTERED_LEAKS.addAndGet(countAllFilteredCalls(out, sampler));
        TERRAIN_RAW_FETCHES.addAndGet(countRawFetches(out, sampler));
        return out;
    }

    private enum TerrainCallKind { BASIC, GRAD, LOD }

    private record TerrainCallSite(int functionStart, int functionEnd,
                                   TerrainCallKind kind, String uvExpression) {}

    private static List<TerrainCallSite> findTerrainCalls(String source, String sampler) {
        String q = Pattern.quote(sampler);
        Pattern call = Pattern.compile(
                "(?<![A-Za-z0-9_])"
                        + "(texture2DGradARB|texture2DGrad|textureGrad|texture2DLodARB|texture2DLod|textureLod|texture2D|texture)"
                        + "\\s*\\(\\s*" + q + "\\s*,"
        );
        Matcher m = call.matcher(source);
        List<TerrainCallSite> result = new ArrayList<>();
        while (m.find()) {
            int uvStart = m.end();
            int uvEnd = findArgumentEnd(source, uvStart);
            if (uvEnd <= uvStart) continue;
            String fn = m.group(1);
            TerrainCallKind kind = fn.contains("Grad") ? TerrainCallKind.GRAD
                    : fn.contains("Lod") ? TerrainCallKind.LOD : TerrainCallKind.BASIC;
            result.add(new TerrainCallSite(m.start(1), m.end(1), kind,
                    source.substring(uvStart, uvEnd)));
        }
        return result;
    }

    /** Find the next top-level comma/closing paren of one GLSL argument expression. */
    private static int findArgumentEnd(String source, int start) {
        int paren = 0, bracket = 0, brace = 0;
        boolean string = false;
        char quote = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (string) {
                if (c == '\\') { i++; continue; }
                if (c == quote) string = false;
                continue;
            }
            if (c == '\'' || c == '"') { string = true; quote = c; continue; }
            switch (c) {
                case '(' -> paren++;
                case ')' -> {
                    if (paren == 0 && bracket == 0 && brace == 0) return i;
                    paren--;
                }
                case '[' -> bracket++;
                case ']' -> bracket--;
                case '{' -> brace++;
                case '}' -> brace--;
                case ',' -> { if (paren == 0 && bracket == 0 && brace == 0) return i; }
                default -> { }
            }
        }
        return -1;
    }

    private static String normalizeExpression(String expression) {
        return expression.replaceAll("\\s+", "");
    }

    private static long countAllFilteredCalls(String source, String sampler) {
        return findTerrainCalls(source, sampler).size();
    }

    private static long countRawFetches(String source, String sampler) {
        String q = Pattern.quote(sampler);
        Pattern raw = Pattern.compile("(?<![A-Za-z0-9_])texelFetch[A-Za-z0-9_]*\\s*\\(\\s*" + q + "\\s*,");
        return countMatches(raw, source);
    }

    private static long countMatches(Pattern pattern, String source) {
        long n = 0;
        Matcher m = pattern.matcher(source);
        while (m.find()) n++;
        return n;
    }

    public static String terrainStatsString() {
        return "s=" + lastTerrainSampler
                + " main=" + TERRAIN_BASIC_REWRITES.get() + "/" + TERRAIN_GRAD_REWRITES.get() + "/" + TERRAIN_LOD_REWRITES.get()
                + " aux=" + TERRAIN_FILTERED_LEAKS.get() + "/" + TERRAIN_RAW_FETCHES.get();
    }

    private static String chooseDiffuseSampler(Set<String> declared) {
        for (String preferred : List.of("gtexture", "Sampler0", "u_BlockTex", "albedo", "diffuse", "diffuseTex", "texture", "tex")) {
            if (declared.contains(preferred)) return preferred;
        }
        for (String name : declared) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("light") || n.contains("normal") || n.contains("spec") || n.contains("shadow")
                    || n.contains("noise") || n.contains("depth") || n.contains("overlay")
                    || n.contains("colortex") || n.contains("gaux") || n.equals("sampler1") || n.equals("sampler2")) {
                continue;
            }
            return name;
        }
        return null;
    }

    private static String rewriteNamedCalls(String source, Pattern call, String replacementFunction) {
        Matcher matcher = call.matcher(source);
        StringBuffer out = new StringBuffer(source.length() + 64);
        int replacements = 0;
        while (matcher.find()) {
            String replacement = replacementFunction + "(" + matcher.group(1) + ",";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            replacements++;
        }
        matcher.appendTail(out);
        return replacements == 0 ? source : out.toString();
    }

    private static String rewriteDiffuseSamples(String source, String replacementFunction) {
        Set<String> declared = new LinkedHashSet<>();
        Matcher declaration = UNIFORM_SAMPLER_2D.matcher(source);
        while (declaration.find()) declared.add(declaration.group(1));
        if (declared.isEmpty()) return source;

        List<String> candidates = new ArrayList<>();
        // One diffuse/albedo sampler per shader is enough. Patching every sampler whose
        // name merely looks plausible can accidentally run xBRZ over an auxiliary map.
        for (String preferred : List.of("gtexture", "Sampler0", "u_BlockTex", "albedo", "diffuse", "diffuseTex", "texture", "tex")) {
            if (declared.contains(preferred)) {
                candidates.add(preferred);
                break;
            }
        }
        if (candidates.isEmpty()) {
            for (String name : declared) {
                String n = name.toLowerCase(Locale.ROOT);
                if (n.contains("light") || n.contains("normal") || n.contains("spec") || n.contains("shadow")
                        || n.contains("noise") || n.contains("depth") || n.contains("overlay")
                        || n.contains("colortex") || n.contains("gaux") || n.equals("sampler1") || n.equals("sampler2")) {
                    continue;
                }
                candidates.add(name);
                break;
            }
        }
        if (candidates.isEmpty()) return source;

        String alternation = candidates.stream().map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElseThrow();
        Pattern call = Pattern.compile("(?<![A-Za-z0-9_])(?:texture2D|texture)\\s*\\(\\s*(" + alternation + ")\\s*,");
        Matcher matcher = call.matcher(source);
        StringBuffer out = new StringBuffer(source.length() + 64);
        int replacements = 0;
        while (matcher.find()) {
            String replacement = replacementFunction + "(" + matcher.group(1) + ",";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            replacements++;
        }
        matcher.appendTail(out);
        return replacements == 0 ? source : out.toString();
    }

    /**
     * Inject helper declarations immediately after the GLSL header, before any user
     * function definitions.  Iris shaderpacks commonly call texture(...) from helper
     * functions declared before main(); injecting only before main() therefore left
     * our replacement function undeclared at the earlier call site and could make the
     * whole shaderpack fail to compile (which in turn leaves Voxy with a 0x0 viewport).
     */
    private static String injectAfterHeader(String source, String helper) {
        if (source == null || helper == null || helper.isEmpty()) return source;

        int version = source.indexOf("#version");
        if (version < 0) {
            // Sodium's already-expanded default source can theoretically arrive without
            // a visible version line.  In that case keep the old safe fallback.
            Matcher main = MAIN_FUNCTION.matcher(source);
            if (!main.find()) return source;
            return source.substring(0, main.start()) + helper + "\n" + source.substring(main.start());
        }

        int insert = source.indexOf('\n', version);
        if (insert < 0) insert = source.length();
        else insert++;

        // Keep immediately following #extension/#pragma directives ahead of our code.
        // #version must remain the first directive in a GLSL translation unit.
        int cursor = insert;
        while (cursor < source.length()) {
            int lineEnd = source.indexOf('\n', cursor);
            if (lineEnd < 0) lineEnd = source.length();
            String line = source.substring(cursor, lineEnd).trim();

            if (line.isEmpty() || line.startsWith("#extension") || line.startsWith("#pragma")) {
                cursor = lineEnd < source.length() ? lineEnd + 1 : lineEnd;
                insert = cursor;
                continue;
            }
            break;
        }

        return source.substring(0, insert) + helper + "\n" + source.substring(insert);
    }

    private static int findMatchingBrace(String source, int brace) {
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private static String terrainShaderHelpers() {
        return """
                /* SMOOTHBLOCKS_XBRZ_DIRECT_V13
                 * xBRZ Freescale: expensive edge classification is precomputed on CPU.
                 * This shader reconstructs directly at the projected screen resolution.
                 */
                uniform usampler2D smoothblocks_Meta;
                uniform int smoothblocks_Mode;
                uniform int smoothblocks_Debug;

                const uint smoothblocks_META_MISSING = 65534u;
                const uint smoothblocks_META_ANIMATED = 65535u;
                const float smoothblocks_SQRT_HALF = 0.7071067811865476;

                float smoothblocks_DistVisible(vec4 a, vec4 b) {
                    // Alpha-aware extension of the validated p1 distance. The lower alpha weights the RGB term and the alpha difference is added
                    // directly, matching the established xBRZ alpha distance. For
                    // a.a==b.a==1 this is exactly the old YCbCr RGB distance.
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
                    // Canonical Freescale p1 geometry, identical to the validated lab's
                    // flat-image path: v = side * length(distv * scale).
                    vec2 p0 = center - origin;
                    vec2 proj = direction * (dot(p0, direction) / dot(direction, direction));
                    vec2 distv = p0 - proj;
                    vec2 orth = vec2(-direction.y, direction.x);
                    float side = sign(dot(p0, orth));
                    float v = side * length(distv * scale);
                    return smoothstep(-smoothblocks_SQRT_HALF, smoothblocks_SQRT_HALF, v);
                }

                vec2 smoothblocks_LocalScale(vec2 pixelSize, vec2 du, vec2 dv) {
                    // Derivative magnitudes convert one source texel on each UV axis to
                    // screen pixels. No full Jacobian is fed into the xBRZ kernel.
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

                uint smoothblocks_ExtraMeta(uint index, ivec2 sourceSize) {
                    return smoothblocks_RawMeta(ivec2(int(index % uint(sourceSize.x)),
                        sourceSize.y + int(index / uint(sourceSize.x))));
                }

                uint smoothblocks_LoadMeta(ivec2 texel, ivec2 sourceSize) {
                    uint meta = smoothblocks_RawMeta(texel);
                    // State 7 is unused by xBRZ; its remaining bits address a sprite descriptor.
                    if ((meta & 7u) != 7u || meta == smoothblocks_META_ANIMATED) return meta;
                    uint descriptor = (meta >> 3u) * 5u;
                    ivec2 origin = ivec2(smoothblocks_ExtraMeta(descriptor, sourceSize),
                                         smoothblocks_ExtraMeta(descriptor + 1u, sourceSize));
                    uint width = smoothblocks_ExtraMeta(descriptor + 2u, sourceSize);
                    uint address = smoothblocks_ExtraMeta(descriptor + 3u, sourceSize)
                        | (smoothblocks_ExtraMeta(descriptor + 4u, sourceSize) << 16u);
                    ivec2 local = texel - origin;
                    return smoothblocks_ExtraMeta(address + uint(local.y) * width + uint(local.x), sourceSize);
                }

                // Explicit level-0 bilinear filtering for animated atlas sprites. XBRZ
                // keeps the hardware sampler NEAREST so static sprites can never become
                // LINEAR through an unpatched texture() path. Atlas padding protects the
                // four taps at sprite borders just as it does for Minecraft's normal
                // linear filtering.
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

                vec4 smoothblocks_Fetch(sampler2D source, ivec2 center, ivec2 offset, uint meta) {
                    if (offset.x < 0 && (meta & (1u << 12u)) != 0u) offset.x = 0;
                    if (offset.x > 0 && (meta & (1u << 13u)) != 0u) offset.x = 0;
                    if (offset.y < 0 && (meta & (1u << 14u)) != 0u) offset.y = 0;
                    if (offset.y > 0 && (meta & (1u << 15u)) != 0u) offset.y = 0;
                    return texelFetch(source, center + offset, 0);
                }

                vec4 smoothblocks_Closer(vec4 center, vec4 a, vec4 b) {
                    float da = smoothblocks_DistVisible(center, a);
                    float db = smoothblocks_DistVisible(center, b);
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

                    uint meta = smoothblocks_LoadMeta(centerTexel, sourceSize);
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
                    vec4 E = smoothblocks_Fetch(source, centerTexel, ivec2( 0, 0), meta);
                    vec4 B = smoothblocks_Fetch(source, centerTexel, ivec2( 0,-1), meta);
                    vec4 D = smoothblocks_Fetch(source, centerTexel, ivec2(-1, 0), meta);
                    vec4 F = smoothblocks_Fetch(source, centerTexel, ivec2( 1, 0), meta);
                    vec4 H = smoothblocks_Fetch(source, centerTexel, ivec2( 0, 1), meta);

                    // Metadata corner mapping: TL bits 0..2, TR 3..5, BR 6..8, BL 9..11.
                    uint tl = smoothblocks_State(meta, 0u);
                    uint tr = smoothblocks_State(meta, 3u);
                    uint br = smoothblocks_State(meta, 6u);
                    uint bl = smoothblocks_State(meta, 9u);

                    if (smoothblocks_Debug == 2) {
                        // EDGES: cyan = at least one xBRZ corner state; dark gray = valid
                        // static metadata with no edge. This directly tests the classifier
                        // and atlas alignment without relying on subjective smoothing.
                        bool hasEdge = (tl | tr | br | bl) != 0u;
                        return hasEdge ? vec4(0.0, 1.0, 1.0, 1.0) : vec4(0.08, 0.08, 0.08, 1.0);
                    }

                    // Canonical validated Freescale p1: always begin from the center
                    // texel E. Only the four xBRZ corner operations may change the result.
                    // No bilinear/linear base is allowed here.
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

                    // Straight-alpha output reconstructed from premultiplied RGBA.
                    // On opaque sprites resAlpha is exactly 1 and this reduces to the
                    // validated RGB-only p1 result. On cutout/translucent silhouettes the
                    // same xBRZ edge geometry now drives alpha as well.
                    vec3 outRgb = resAlpha > 1e-6 ? resPremul / resAlpha : E.rgb;
                    return vec4(clamp(outRgb, 0.0, 1.0), clamp(resAlpha, 0.0, 1.0));
                }
                """;
    }


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

    private static String entityShaderHelpers() {
        // One canonical p1 implementation for terrain, entities and world items.
        // Only the uniform namespace differs; classification always runs at resource load.
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
