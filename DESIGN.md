# SmoothBlocks 1.6.3 design

## Opaque source of truth

The validated Java Freescale lab remains authoritative for opaque images. `SmoothBlocksXbrzCore` is deliberately constructed so alpha=255 input produces byte-identical 16-bit metadata to v1.6.2/the lab.

## Stage A — atlas metadata

After the block atlas is uploaded, SmoothBlocks reads the exact OpenGL level-0 RGBA8 atlas. Static sprites are cropped by content UV origin + logical dimensions and classified independently. The RG8UI metadata atlas stores TL/TR/BR/BL states plus hard sprite boundaries.

`0xFFFF` = animated sprite (explicit bilinear). `0xFFFE` = missing/padding (NEAREST).

### Alpha extension

For color distance, use the established xBRZ alpha form:

```text
d = min(alphaA, alphaB) * dYCbCr(rgbA, rgbB) + abs(alphaA - alphaB)
```

with normalized alpha in the shader / equivalent 0..255 math on CPU. Fully opaque input is exactly the original xBRZ distance. Fully transparent hidden RGB cannot create false edges.

## Stage B — direct reconstruction

The canonical Freescale geometry remains:

```glsl
float v = side * length(distv * scale);
```

RGB and alpha are reconstructed together. Accumulation is performed in premultiplied RGBA, then converted back to straight alpha for Minecraft's shader pipeline. For opaque sprites alpha remains 1, so p1 is mathematically the same RGB reconstruction as before.

Minecraft derives the two-component local screen-pixels-per-source-texel scale from UV derivative magnitudes. No full-Jacobian experiment is used in the xBRZ kernel.

## Iris integration

Only diffuse-atlas calls directly inside transformed fragment `main()` are xBRZ-wrapped. All such direct main calls are wrapped because shaderpacks may select different albedo UV expressions by material. Helper-function atlas reads remain on the shaderpack path with the hardware sampler forced NEAREST in XBRZ mode.

This is narrower than v1.6.1 (which rewrote helper probes and broke atlas association) and broader than v1.6.2 (which only wrapped the first UV-expression family and left material-specific NEAREST islands).
