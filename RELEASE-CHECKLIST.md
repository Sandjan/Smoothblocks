# Acceptance before stable 1.0

Current candidate: 1.0.0-alpha.4. Keep the initial alpha.1 tag as a rollback baseline. Do not declare the two reported visual defects fully resolved until this matrix passes in-game.

## Automated checks

- `gradlew.bat build`: shader fixtures, atlas addressing, actual Minecraft resources and hook descriptors.
- `gradlew.bat mixinRegression`: real Fabric/Mixin transformation of vanilla targets without a game window. This intentionally does not simulate Sodium or Iris implementations.
- `gradlew.bat gpuRegression`: hidden OpenGL 4.5 rendering, pixel parity, actual world/GUI mode switching, metadata lifecycle and GL state restoration.

## In-game acceptance (pending)

Alpha.4 intentionally omits generated 2D-item side walls in world XBRZ mode. Check front/back contours and the accepted edge-on thin appearance; these items are no longer closed extrusions. Verify that disabling XBRZ restores the walls and that GUI and block items are unaffected.

For each of Fabric + Sodium, Fabric + Sodium + Iris with shaderpack disabled, and Iris with shaderpack enabled:

1. Check static blocks, cutout leaves/grass, animated terrain, entities, player skins, armor and block entities.
2. Rotate held tools/items and view their narrow sides. Check dropped items and item frames. Colors and alpha should interpolate smoothly; check side faces for holes or seams. Entirely smooth 3D item contours are outside this conservative fix.
3. Check inventory, hotbar, creative menu, item tooltips, all GUI text, player preview and other GUI entity previews. Compare mod enabled/disabled: these must not change.
4. Switch NEAREST/LINEAR/XBRZ and disable/re-enable the mod. Change shaderpack, disable/re-enable shaders, reload resources, change resource pack and reconnect to a world.
5. Check changing skins/capes and dynamic textures. F7 reports entity metadata count/build count; looking around an unchanged scene must not continually increase the build count.
6. Compare frame times in the same scene, including many entities and a large held item. Measure reload latency separately. No FPS improvement is claimed by the automated tests.

Animated atlas sprites retain the established bilinear sentinel fallback, including the new item/entity atlases. Unknown custom standalone texture paths or shaderpack sampling patterns may still need integration coverage. No change to mip selection was made.

## Architecture notes

- P0: resource upload/registration builds metadata using SmoothBlocksXbrzCore. Standalone upload invalidations are coalesced before the next world render. A dirty entry falls back instead of using stale metadata.
- P1: SmoothBlocksShaderPatch generates one canonical reconstruction kernel, namespaced for terrain or entities. No entity classifier remains in GLSL.
- Terrain keeps its existing atlas path. World items use the dedicated item atlas; other entity atlases are classified sprite by sprite.
- The entity setup wraps the call to trySetup, so it follows both vanilla and Iris setup, including cancellation/early returns. Metadata bindings are scoped to the actual draw and restored afterward.
- Iris setup ordering was checked against its upstream 26.1 source: https://github.com/IrisShaders/Iris/blob/26.1/common/src/main/java/net/irisshaders/iris/mixin/MixinGlCommandEncoder.java

## Packaging follow-up

Confirm the exact tested Sodium/Iris versions and shaderpacks before publishing compatibility claims. Pin the Loom version for reproducible releases, finalize project authors/contact/source links and third-party attribution, and consider configurable/persistent keybindings. No GitHub publication is performed by this work.
