# SmoothBlocks validation

## 1.0.0-alpha.3 — 2026-10-08

`build gpuRegression` passed. The existing item check now requires interpolated edge alpha and full RGBA parity with the shared terrain/entity kernel, replacing alpha.2's original-coverage assertion. GUI pixel parity and sprite-boundary checks still pass. In-game visual confirmation is pending.

## 1.0.0-alpha.2 — 2026-10-07

Run `gradlew.bat build mixinRegression gpuRegression` with Java 25 (GPU task needs a local OpenGL 4.5 driver). Validated locally with Gradle 9.8.0, Fabric Loader 0.19.5 and Intel UHD Graphics.

- Existing shader patch harness and 727,506 atlas checks pass.
- Real Minecraft 26.1.2 entity/item fragment resources are selected and patched idempotently. GUI/text shaders are excluded.
- Target descriptors are checked against the Minecraft JAR. A separate Fabric launch transforms all vanilla target classes with Mixin verification enabled, then exits before game startup. It uses a test-only Sodium dependency alias, not the actual Sodium/Iris implementations.
- Offscreen GLSL compilation/linking and RGBA pixel readback pass. The shared p1 kernel gives identical terrain/entity outputs; vanilla and two-phase Iris patch fixtures match.
- The actual entity bridge switches the same program/texture between world xBRZ and GUI-original output. GUI output is byte-identical to the original shader; GUI and auxiliary samplers are not replaced.
- Item alpha matches original coverage; adjacent atlas sprite colors do not leak across borders.
- Metadata lookups/unchanged frames do not build. Dirty textures are unavailable until refreshed, repeated invalidations coalesce, identical uploads skip classification, changed uploads rebuild once, disposal removes metadata.
- Shared GL binding restores the previous texture, sampler and active unit.

Limits: no in-game world or actual shaderpack was rendered in these tests, no FPS benchmark, no visual acceptance of held item side faces. See RELEASE-CHECKLIST.md. The initial review remains a historical snapshot of alpha.1.

## Historical internal 1.6.3 validation

No Java/Gradle installation was attempted in the build environment. Dependency-free core and shader-patcher checks were compiled with the already available JDK.

## Opaque pass-0 parity

A frozen copy of the v1.6.2 core was compared against v1.6.3 on 1,000 random fully opaque images / 261,078 texels.

```text
OPAQUE_PARITY_OK images=1000 texels=261078
```

Therefore adding alpha awareness does not change a single packed metadata bit for opaque textures.

## Alpha classifier checks

A diagonal silhouette whose RGB is identical on both sides but whose alpha is 255/0 produces no edges with the old RGB-only classifier and 29 edge texels with the alpha-aware classifier. Randomizing hidden RGB under alpha=0 does not change metadata.

```text
ALPHA_VERTICAL oldEdges=0 newEdges=0
ALPHA_DIAGONAL oldEdges=0 newEdges=29
TRANSPARENT_RGB_INVARIANCE_OK
```

A straight vertical boundary legitimately needs no xBRZ corner state; the diagonal silhouette is the important regression case.

## Shader patch harness

The post-Iris harness verifies that every direct `gtexture` lookup in `main()` is wrapped, while a helper-function POM-style lookup remains untouched. It also checks canonical `length(distv * scale)` geometry and absence of the terrain full-Jacobian experiment.

```text
PASS shader-patch v1.6.3 chars=11706 stats=s=gtexture main=2/1/1 aux=1/0
```

## Atlas mapping harness

The existing RG8UI addressing/packing stress test still passes:

```text
PASS atlas-map iterations=500 checks=727506 staticTexels=727506 animatedTexels=129511
```
