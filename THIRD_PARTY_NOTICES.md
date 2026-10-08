# Third-party notices

SmoothBlocks' direct edge-classification/reconstruction design is based on the established xBRZ / xBRZ Freescale family.

Reference implementations consulted:

- xBRZ by Zenju and ports/derivatives of xBRZ. xBRZ implementations are distributed under GPL-family licensing depending on the implementation.
- xBRZ Freescale shader as distributed by the libretro/bsnes shader collections. The consulted libretro xbrz-freescale.glsl header identifies its xBRZ-derived portion as GPL v3 with a MAME-specific linking exception, and Hyllian's vertex/texel-mapping portion under the MIT license. SmoothBlocks does not rely on the MAME exception.
- Hyllian's xBR work, Copyright (C) 2011/2016 Hyllian, MIT-licensed in the referenced shader header.

SmoothBlocks is distributed under GPL-3.0-or-later so that the xBRZ-derived implementation can be redistributed under compatible terms.

Project references:
- https://github.com/libretro/glsl-shaders/tree/master/xbrz
- https://github.com/bsnes-emu/bsnes/tree/master/shaders/xBRZ.shader
- https://github.com/stanio/xbrz-java
