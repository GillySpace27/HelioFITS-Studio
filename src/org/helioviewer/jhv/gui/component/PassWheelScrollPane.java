package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.event.MouseWheelListener;

import javax.swing.JScrollPane;

/**
 * A scroll pane that never takes the mouse wheel, for a list sized to all its rows inside a
 * scrolling sidebar. AWT hands a wheel event to the deepest component under the pointer that has a
 * wheel listener, and every look and feel installs one on a JScrollPane. setWheelScrollingEnabled(false)
 * only makes that listener ignore the event, so the event still stopped there and the sidebar
 * stuck wherever the list passed under the pointer (seen with Robot under Xvfb, 2026-11-23). With
 * no listener the event goes on up to the sidebar's own scroll pane. updateUI runs again on every
 * theme switch, which installs the listener again, so it is removed there.
 */
@SuppressWarnings("serial")
public final class PassWheelScrollPane extends JScrollPane {

    public PassWheelScrollPane(Component view, int vsbPolicy, int hsbPolicy) {
        super(view, vsbPolicy, hsbPolicy);
        setWheelScrollingEnabled(false);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        for (MouseWheelListener l : getMouseWheelListeners())
            removeMouseWheelListener(l);
    }

}
