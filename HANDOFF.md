# SmoothBlocks handoff — v1.6.3

Target: Minecraft 26.1.2, Fabric 0.19.5, Iris/Sodium, Java 25.

## Established facts

- The standalone Java Freescale lab is validated on Minecraft/pixel-art textures.
- v1.6.2 fixed the catastrophic atlas/UV association bug. Runtime invariants were healthy: `fb0`, `src=ok`, `dr=0`, `uv0`, `ov0`, `up0`.
- Remaining defects were pixelated alpha silhouettes and material-specific residual NEAREST-looking islands.

## v1.6.3 changes

- Pass 0 uses established xBRZ alpha-aware color distance while remaining bit-identical for fully opaque input.
- Pass 1 blends premultiplied RGBA, so alpha follows the same xBRZ edge geometry as RGB.
- All direct diffuse-atlas calls in Iris fragment `main()` are xBRZ-wrapped, not only calls whose UV text matches the first sample. Helper-function probes remain untouched.
- Entity p1 uses the same canonical `vec2 scale` Freescale geometry as terrain; the stale full-Jacobian entity path is gone.
- Animated bilinear uses premultiplied RGBA.
- GPU/SpriteContents source diagnostics compare RGBA.

Controls: P mode, F6 OFF/PATH/EDGES, F7 compact report, F8 toggle, F9 reset.

Build on the user's Java-25 setup with `gradle clean build`.
