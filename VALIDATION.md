# Validation

Use Java 25 and the included Gradle wrapper. On Windows replace `./gradlew` with `gradlew.bat`.

- `./gradlew build`: shader patch fixtures, atlas mapping, actual Minecraft 26.1.2 resources and hook descriptors.
- `./gradlew mixinRegression`: transform the vanilla targets through Fabric/Mixin, then exit before starting a game window. By default a test-only Sodium alias satisfies dependency resolution.
- `./gradlew mixinRegression -PsmokeMods=<absolute-Sodium-jar-path>`: additionally test a complete generated-item bake with actual Sodium 0.9.1, retaining both main faces and omitting only generated walls in world rendering.
- `./gradlew gpuRegression`: use a hidden OpenGL 4.5 window to check shared vanilla/Iris/terrain reconstruction, GUI pixel parity, item alpha, sprite isolation, GL state restoration and metadata cache lifecycle.

The GPU harness also checks precomputed animation frame addressing and periodic block reconstruction against the center tile of a repeated image. These fixtures do not launch a Minecraft world or render a complete real shaderpack.

Builds and GPU checks have passed locally with Java 25, Gradle 9.8.0, Fabric Loader 0.19.5 and Intel UHD Graphics. Actual Sodium item baking was checked with Sodium 0.9.1. The user confirmed the repaired generated-item appearance in-game during alpha development. No systematic FPS benchmark or exhaustive shaderpack compatibility matrix has been completed.

Test-only JARs under `build/validation` must never be published as the mod.