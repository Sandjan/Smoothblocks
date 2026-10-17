# Changelog

## Unreleased — 2026-10-17

- Refactor animation preparation, CPU atlas classification and GPU publication into cohesive steps; publish matching CPU/GPU generations after successful upload.
- Bound long animation address tables by quantized interpolation weights and enforce metadata capacity while retaining maps.
- Fix shader rewriting around comments and quoted text, and reject overflowing image dimensions before indexing.
- Consolidate sampler ownership and private sprite-field access; restore temporary metadata bindings on exceptional draw exits and report partial alpha-repair failures.
- Remove unused diagnostics, obsolete classifier/terrain adapters, the superseded shader-coverage audit and the completed review report.
- Extend validation with isolated atlas/overflow/comment checks, long-animation and transfer-state cases, and Sodium RGSS pixel comparisons.
- Refresh build, validation and publication documentation after cleanup. The project version remains 1.0.0; these changes have not been assigned a new release.

Entries below describe historical releases and their limitations at the time.

## 1.0.0 — 2026-10-08

- First public release for Minecraft 26.1.2 / Fabric with required Sodium and optional Iris.
- Remove all keyboard hooks, sampling-mode switches and in-game diagnostic controls. xBRZ is always active for supported world rendering; GUI remains unchanged.
- Include animated block metadata, sprite-local periodic interpolation for opaque blocks and repaired generated-item contours from the alpha releases.
- Update installation, compatibility, architecture and validation documentation; remove obsolete development handoff files and saved test output. Pin Fabric Loom to 1.18.2.