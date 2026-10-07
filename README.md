# SmoothBlocks 1.0.0-alpha.1

Initial versioned alpha, based on the internal 1.6.3 development snapshot. This is not the stable 1.0 release.

Known issues: entity interpolation without an active shaderpack and malformed edges on held items. See [ANALYSE-1.0.md](ANALYSE-1.0.md) for the investigation and [CHANGELOG.md](CHANGELOG.md) for release history.

Target: Minecraft 26.1.2, Fabric Loader 0.19.5, Sodium/Iris, Java 25.

The standalone Java lab remains the source of truth for opaque Freescale xBRZ. v1.6.3 keeps the opaque pass-0 metadata **bit-identical** to v1.6.2/the validated lab and fixes two Minecraft-only gaps that were still visible after the atlas-association repair.

## Controls

- **P**: NEAREST -> LINEAR -> XBRZ (XBRZ is default)
- **F6**: OFF -> PATH -> EDGES -> OFF
- **F7**: compact four-line diagnostics
- **F8**: enable/disable SmoothBlocks
- **F9**: reset runtime counters

## Main v1.6.3 changes

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

Expected jar: `build/libs/smoothblocks-1.0.0-alpha.1.jar`.
