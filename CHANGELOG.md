# Changelog

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
