# Publication checklist

Release: **1.0.0**, Minecraft **26.1.2**, **Fabric**, requires **Sodium**; Iris optional.

- Build with Java 25: `gradlew.bat build mixinRegression gpuRegression`.
- Publish `build/libs/smoothblocks-1.0.0.jar` with the 1.0.0 changelog. The sources JAR may accompany it; test-only JARs must not.
- Include the source repository and GPL-3.0-or-later license information with distribution. Keep THIRD_PARTY_NOTICES.md.
- Set the publication's game version, loader and Sodium dependency accurately; do not list Iris as required.

Suggested final gameplay checks: shaderpack on/off, a resource reload, animated opaque blocks, cutout foliage, entities and held/dropped items. Confirm GUI rendering remains unchanged and P/F6/F7/F8/F9 trigger no SmoothBlocks action. Compare frame times and resource-load memory separately if publishing performance claims.

No GitHub repository URL is embedded until the owner creates the repository. Publication is performed by the owner.