# Changelog

## 1.0.0-alpha.4 — 2026-10-08

- Omit only automatically generated item side walls during world XBRZ rendering, including glint/outline submissions. These per-pixel walls stretch an inset texel across the item thickness and conflict with the smooth front/back alpha contour.
- Keep smooth front/back faces, GUI geometry, actual 3D models and original geometry in disabled/NEAREST/LINEAR modes. Generated items now have open sides and can look very thin edge-on; this is intentional.

## 1.0.0-alpha.3 — 2026-10-08

- Remove the item-only override that restored unfiltered alpha after xBRZ. World/held items now use the same premultiplied RGBA reconstruction as terrain cutouts such as grass.
- Keep sprite boundaries, GUI exclusion and mip-level policy unchanged.
- Update the existing GPU regression to verify interpolated item alpha and RGBA parity with the shared kernel. In-game side-face appearance still needs confirmation.

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
