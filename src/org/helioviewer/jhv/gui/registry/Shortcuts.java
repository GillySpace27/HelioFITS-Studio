package org.helioviewer.jhv.gui.registry;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.KeyStroke;

import org.helioviewer.jhv.app.Platform;

/** A keystroke as a person reads it: Cmd+K as the symbols on a Mac, Ctrl+K elsewhere. */
public final class Shortcuts {

    public static String text(KeyStroke ks) {
        int m = ks.getModifiers();
        String key = KeyEvent.getKeyText(ks.getKeyCode());
        if (Platform.isMacOS()) {
            return ((m & InputEvent.CTRL_DOWN_MASK) != 0 ? "⌃" : "") + ((m & InputEvent.ALT_DOWN_MASK) != 0 ? "⌥" : "")
                    + ((m & InputEvent.SHIFT_DOWN_MASK) != 0 ? "⇧" : "") + ((m & InputEvent.META_DOWN_MASK) != 0 ? "⌘" : "") + key;
        }
        StringBuilder sb = new StringBuilder();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0)
            sb.append("Ctrl+");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0)
            sb.append("Alt+");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0)
            sb.append("Shift+");
        if ((m & InputEvent.META_DOWN_MASK) != 0)
            sb.append("Meta+");
        return sb.append(key).toString();
    }

    private Shortcuts() {}
}
