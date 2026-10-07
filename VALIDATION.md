# SmoothBlocks 1.6.3 validation

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
