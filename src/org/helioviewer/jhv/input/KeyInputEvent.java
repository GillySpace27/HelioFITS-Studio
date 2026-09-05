package org.helioviewer.jhv.input;

/** A key event with the modifier state AFTER it: on a release, the released key is no longer down. */
public record KeyInputEvent(Key key, boolean shiftDown, boolean metaDown, boolean altDown, boolean ctrlDown) {
    public enum Key {
        OTHER,
        BACKSPACE,
        DELETE,
        N,
        P
    }
}
