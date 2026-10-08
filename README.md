# SmoothBlocks 1.0.0-alpha.7

Development alpha for Minecraft 26.1.2. Fabric and Sodium are required; Iris is optional. This is not the stable 1.0 release.

Alpha.2 addresses the shader-loading gap and item-atlas boundaries identified in the initial review. In-game acceptance is still pending; see [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md), [VALIDATION.md](VALIDATION.md) and [CHANGELOG.md](CHANGELOG.md).

Only world rendering is filtered: terrain, entities and world/held items. GUI, text, inventory/hotbar items and GUI entity previews retain their original rendering, even when they share a shader or texture with world objects.

In XBRZ mode, generated 2D world items omit their artificial side walls; their front/back alpha stays smooth. These items have open sides and may disappear edge-on. GUI, true 3D models and other sampling modes keep their original geometry.

Entities now use resource-time metadata and the same reconstruction kernel as terrain. Texture changes invalidate the cached metadata; unchanged draws never classify edges. World items use the same interpolated RGBA as terrain cutouts, including alpha at their edges. Mip-level 0 and the animated-atlas fallback are unchanged.

Target: Minecraft 26.1.2, Fabric Loader 0.19.5, Sodium/Iris, Java 25.

The standalone Java lab remains the source of truth for opaque Freescale xBRZ. v1.6.3 keeps the opaque pass-0 metadata **bit-identical** to v1.6.2/the validated lab and fixes two Minecraft-only gaps that were still visible after the atlas-association repair.

## Controls

- **P**: NEAREST -> LINEAR -> XBRZ (XBRZ is default)
- **F6**: OFF -> PATH -> EDGES -> OFF
- **F7**: compact four-line diagnostics
- **F8**: enable/disable SmoothBlocks
- **F9**: reset runtime counters

## Inherited algorithm (internal v1.6.3 baseline)

1. **Alpha is now part of xBRZ edge classification.** The distance follows the established xBRZ alpha construction: the lower alpha weights the YCbCr RGB distance and the alpha difference is added directly. Two opaque pixels therefore use exactly the old validated RGB distance; two fully transparent pixels compare equal regardless of hidden RGB.
2. **Pass 1 reconstructs RGBA, not RGB-only.** xBRZ blends premultiplied RGB + alpha and converts back to straight alpha at the output. This smooths grass/leaves/cutout silhouettes without black transparent fringes. Opaque sprites reduce exactly to the old RGB path.
3. **Every diffuse-atlas lookup directly inside the final Iris `main()` is now xBRZ-wrapped, even when its UV expression differs.** v1.6.2 only wrapped calls matching the first UV expression; shaderpacks can choose a different final albedo coordinate for specific materials, leaving block-specific NEAREST islands. Helper-function atlas probes remain untouched so POM/material coordinate logic is not fed xBRZ-filtered data.
4. **Entity xBRZ now uses the same canonical `vec2 scale` Freescale geometry as terrain.** The old entity-only full-Jacobian experiment is removed.
5. **Animated bilinear is alpha-correct.** It interpolates premultiplied RGBA; static terrain still has no LINEAR fallback.
6. The GPU-vs-SpriteContents diagnostic now compares full **RGBA**, not only RGB.

## Static terrain invariant

In XBRZ mode a static block texture can only use xBRZ or NEAREST on an explicit error/missing path. LINEAR is reserved for the `META_ANIMATED` sentinel. Sodium RGSS is bypassed and the backing atlas sampler remains NEAREST.

## F6 / F7

`PATH`: green = valid static xBRZ metadata; yellow = animated; magenta = missing/padding; red = atlas-size mismatch.

`EDGES`: cyan = at least one packed xBRZ corner state; dark gray = valid static texel without one.

Healthy F7 still has `fb0`, `p/m/u=0/0/0`, `src=ok`, `dr=0`, `uv0`, `ov0`, `up0`.

Build on the Java-25 machine:

```powershell
.\gradlew.bat clean build
```

On Linux/macOS, use `./gradlew clean build` with Java 25.

Expected jar: `build/libs/smoothblocks-1.0.0-alpha.7.jar`.

`build` includes the portable regression harnesses. Additional local checks:

```powershell
.\gradlew.bat mixinRegression
.\gradlew.bat gpuRegression
```

The first exits before Minecraft opens a window and validates vanilla Mixin targets. The second requires OpenGL 4.5 and uses a hidden test window. The test-only dependency alias in `build/validation` is not a mod for distribution.
