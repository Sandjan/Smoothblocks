package de.oai.smoothblocks;

/** Binary contract shared by preparation and GLSL. Addresses count 16-bit cells. */
final class SmoothBlocksMetadataFormat {
    static final int MISSING = 0xFFFE;
    static final int ANIMATED_LINEAR = 0xFFFF;
    static final int DESCRIPTOR_CELLS = 6;
    static final int ADDRESS_CELL = 4;
    static final int MARKER_SHIFT = 3;
    static final int MARKER_STATE = 7;
    static final int WRAP_FLAG = 0x8000;
    static final int WIDTH_MASK = WRAP_FLAG - 1;
    static final int MAX_SPRITES = (ANIMATED_LINEAR >>> MARKER_SHIFT) - 1;

    private SmoothBlocksMetadataFormat() {}

    static int marker(int slot) {
        return (slot << MARKER_SHIFT) | MARKER_STATE;
    }
}
