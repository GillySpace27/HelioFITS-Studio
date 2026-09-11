package org.helioviewer.jhv.gui.component;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;

import org.helioviewer.jhv.app.Settings;

/**
 * Freezes where the panels live, without freezing what they do.
 *
 * <p>A sidebar full of sections carries four buttons per header that move the section: up, down,
 * across to the other sidebar, and out into a window. They are the right controls to have while
 * you are arranging a workspace and the wrong ones to have a stray click land on once it is
 * arranged, because a panel that jumps a slot mid-talk is a panel you now have to find. The lock
 * greys all four, everywhere, and touches nothing else.
 *
 * <p>Collapse and unfold deliberately stay live. Folding a section away is how you make room, not
 * how you rearrange, and it is the one move you keep wanting after the layout is settled.
 *
 * <p>Controls register themselves rather than being hunted down, because the sidebars build and
 * discard header rows as sections come and go, so any list held here would go stale. Registering
 * is one call at the point the button is made, and the state is applied to it at once.
 */
public final class PanelLock {

    private static final String KEY = "ui.panelsLocked";

    private static final List<JComponent> controls = new ArrayList<>();
    private static final List<Runnable> listeners = new ArrayList<>();
    private static boolean locked = "true".equals(Settings.getProperty(KEY));

    /** A control that moves a panel about, to be greyed while the panels are locked. */
    public static void register(JComponent control) {
        controls.add(control);
        control.setEnabled(!locked);
    }

    /** Forget a control whose header has been thrown away, so the list does not grow forever. */
    public static void unregister(JComponent control) {
        controls.remove(control);
    }

    public static boolean isLocked() {
        return locked;
    }

    public static void setLocked(boolean _locked) {
        if (locked == _locked)
            return;
        locked = _locked;
        Settings.setProperty(KEY, Boolean.toString(locked));
        apply();
        listeners.forEach(Runnable::run);
    }

    /** Re-apply to everything registered; also the way a freshly built header picks the state up. */
    public static void apply() {
        for (JComponent control : controls)
            control.setEnabled(!locked);
    }

    /** Told when the lock turns over, so a toolbar button can keep its pressed state honest. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private PanelLock() {}
}
