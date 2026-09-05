package org.helioviewer.jhv.input;

/** A pointer event in canvas pixels, with the modifier keys held at the time. */
public record PointerEvent(int x, int y, int button, int clickCount, boolean shiftDown, boolean popupTrigger,
                           boolean metaDown, boolean altDown, boolean ctrlDown) {}
