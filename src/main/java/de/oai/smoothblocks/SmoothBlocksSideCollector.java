package de.oai.smoothblocks;

/** Bake-time state belongs to the builder, so concurrent model bakes stay independent. */
public interface SmoothBlocksSideCollector {
    void smoothblocks$collectSides(boolean enabled);
}
