# Changelog

## 1.0.0-alpha.2 — 2026-10-07

- Patch Minecraft's actual shader compilation cache, including the dedicated item shader.
- Gate entity/item reconstruction and sampler replacement by world rendering scope; GUI items and entity previews retain original rendering.
- Precompute entity, equipment, dynamic texture and non-GUI atlas metadata. Draws no longer classify entity edges. Changed standalone uploads are coalesced before world rendering; unchanged uploads skip classification.
- Reuse the terrain reconstruction kernel and scoped metadata binding code for entities and items.
- Respect individual sprite boundaries in the item atlas; preserve its original alpha coverage to avoid mismatches with generated item side faces.
- Configure entity metadata after the complete Minecraft/Iris setup call, including Iris early-return paths.
- Preserve auxiliary samplers and diffuse sampler wrap/LOD/anisotropy settings; cache pipeline classification, descriptions and hardware limits.
- Add automated shader/atlas/integration checks, an isolated Fabric/Mixin smoke test and opt-in offscreen GPU regression tests.
- Target Minecraft 26.1.2 explicitly. Terrain sampling and mip-level policy remain unchanged.

Still an alpha: in-game visual acceptance with and without shaderpacks is pending. GPU tests exercise generated shaders, not arbitrary real shaderpacks. Animated atlas sprites retain the existing bilinear fallback. No item mesh/silhouette redesign is included.

## 1.0.0-alpha.1 — 2026-10-07

Initial version-controlled alpha based on the internal 1.6.3 development snapshot.

- Minecraft 26.1.2 / Fabric / Sodium, with Iris integration.
- xBRZ terrain reconstruction using precomputed atlas metadata.
- Shader-based entity interpolation, alpha-aware reconstruction and runtime diagnostics.
- Includes the pre-release code review and shader coverage audit.

### Known issues

- Entity interpolation does not reliably activate without an enabled shaderpack.
- Held item edges can render incorrectly.
- The dedicated Minecraft item shader is not yet covered by the shader selection.

No rendering bug fixes are included in this versioning baseline. The stable 1.0.0 release is reserved for a subsequent validated build.
