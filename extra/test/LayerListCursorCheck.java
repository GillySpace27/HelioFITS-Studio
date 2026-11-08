package org.helioviewer.jhv.layers.selector;

import org.helioviewer.jhv.layers.selector.LayerListCursor.Kind;

/**
 * Layer lists: the open hand is on the grip only (closed while pressed), the pointing hand is on the
 * click controls, and everything else is the arrow.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.selector.LayerListCursorCheck
 */
public final class LayerListCursorCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        expect("grip is an open hand", LayerListCursor.kindAt(LayersPanel.HANDLE_COL, true, false) == Kind.OPEN_HAND);
        expect("pressed grip is a closed hand", LayerListCursor.kindAt(LayersPanel.HANDLE_COL, true, true) == Kind.CLOSED_HAND);
        expect("name is an arrow", LayerListCursor.kindAt(LayersPanel.NAME_COL, true, false) == Kind.ARROW);
        expect("time is an arrow", LayerListCursor.kindAt(LayersPanel.TIME_COL, true, true) == Kind.ARROW);
        expect("show checkbox is a pointer", LayerListCursor.kindAt(LayersPanel.ENABLED_COL, true, false) == Kind.POINTER);
        expect("drive radio is a pointer", LayerListCursor.kindAt(LayersPanel.MASTER_COL, true, true) == Kind.POINTER);
        expect("remove x is a pointer", LayerListCursor.kindAt(LayersPanel.REMOVE_COL, true, false) == Kind.POINTER);
        expect("below the last row is an arrow", LayerListCursor.kindAt(LayersPanel.HANDLE_COL, false, false) == Kind.ARROW);
        expect("outside every column is an arrow", LayerListCursor.kindAt(-1, true, false) == Kind.ARROW);
        expect("grip is not a click control", !LayerListCursor.isClickControl(LayersPanel.HANDLE_COL));

        // Wheel pass-through: a control that listens for the wheel swallows the scroll meant for the sidebar.
        java.util.List<javax.swing.JComponent> controls = java.util.List.of(
                new org.helioviewer.jhv.gui.component.JHVSlider(0, 10, 5),
                new org.helioviewer.jhv.gui.component.JHVSpinner(new javax.swing.SpinnerNumberModel(1, 0, 9, 1)));
        for (javax.swing.JComponent c : controls)
            expect(c.getClass().getSimpleName() + " has no wheel listener", c.getMouseWheelListeners().length == 0);

        System.out.println(failures == 0 ? "LayerListCursorCheck: ok" : "LayerListCursorCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
