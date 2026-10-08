# Rendering design

SmoothBlocks separates resource-time edge classification (P0) from fragment reconstruction (P1). The alpha-aware classifier and premultiplied RGBA reconstruction are shared by terrain, entities and world items. GUI rendering is excluded by render scope as well as shader/pipeline selection.

## Metadata

The block atlas is captured at GPU mip level 0 after upload. Sprites are classified independently. RG8UI metadata packs four three-bit corner states and four boundary bits. `0xFFFE` marks missing/padding data; `0xFFFF` retains linear filtering for animated effects. The unused corner state 7 addresses a descriptor stored below the atlas metadata.

Each descriptor contains sprite origin, width/wrap flag, height and a 32-bit metadata address. Opaque block sprites use periodic classification and sprite-local wrapping. Static interior texels retain direct metadata; only their borders resolve a descriptor. Transparent block sprites and other texture classes retain hard boundaries.

Animated blocks precompute their distinct frames and Minecraft's quantized temporal intermediate steps at resource load. Identical metadata shares storage. Rendering selects the active frame by updating two RG8UI address texels (four bytes), without copying or classifying frame metadata. Water, lava, nether portal and fire retain the linear path. GPU texture-size limits still apply.

Other world texture metadata is cached by texture identity. Supported standalone uploads invalidate the cache and are coalesced before world rendering. Draws only look up prepared data. Closing textures or reloading resources disposes the associated metadata and caches.

## Rendering integration

Vanilla shader sources are patched in ShaderManager's compilation cache. Sodium terrain and Iris shader transformation hooks reuse the canonical P1 kernel. Metadata setup follows the full draw setup call, including Iris early returns, and restores temporary sampler/texture bindings afterward. Mip selection policy is unchanged.

Generated-item sides are marked through the common QuadCollection builder around the side-bake call. This includes Sodium's replacement baker. Vanilla and Sodium/Fabric Renderer API item output omit only those marked side faces during world xBRZ rendering. Front/back, GUI and actual 3D models retain their geometry.

The release has a fixed xBRZ policy and no keyboard hooks or in-game diagnostic controls. Internal shader uniforms and fallback paths remain available to the regression harnesses and error handling.