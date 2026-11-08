package org.helioviewer.jhv.input;

/**
 * A key event with the modifier state AFTER it: on a release, the released key is no longer down.
 * {@code code} is the AWT key code, for the keys that can be rebound and so have no case in {@link Key}.
 */
public record KeyInputEvent(Key key, boolean shiftDown, boolean metaDown, boolean altDown, boolean ctrlDown, int code) {
    public enum Key {
        OTHER,
        BACKSPACE,
        DELETE,
        SPACE,
        LEFT,
        RIGHT,
        N,
        P,
        X,
        Y,
        Z
    }
}
