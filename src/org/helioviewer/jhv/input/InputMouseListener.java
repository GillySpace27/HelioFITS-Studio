package org.helioviewer.jhv.input;

public interface InputMouseListener {
    default void mouseClicked(PointerEvent e) {}

    default void mouseExited(PointerEvent e) {}

    default void mousePressed(PointerEvent e) {}

    default void mouseReleased(PointerEvent e) {}

    default void mouseDragged(PointerEvent e) {}

    default void mouseMoved(PointerEvent e) {}
}
