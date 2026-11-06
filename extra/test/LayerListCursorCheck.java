package org.helioviewer.jhv.layers.selector;

/**
 * The Image Layers list shows the drag hand over the body of an image row and the ordinary arrow
 * over the show checkbox, the drive radio and the remove x, and over overlay rows and empty space.
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
        expect("name column of an image row is a drag hand", LayerListCursor.handOver(LayersPanel.NAME_COL, true));
        expect("time column of an image row is a drag hand", LayerListCursor.handOver(LayersPanel.TIME_COL, true));
        expect("show checkbox is an arrow", !LayerListCursor.handOver(LayersPanel.ENABLED_COL, true));
        expect("drive radio is an arrow", !LayerListCursor.handOver(LayersPanel.MASTER_COL, true));
        expect("remove x is an arrow", !LayerListCursor.handOver(LayersPanel.REMOVE_COL, true));
        expect("name column of an overlay row is an arrow", !LayerListCursor.handOver(LayersPanel.NAME_COL, false));
        expect("outside every column is an arrow", !LayerListCursor.handOver(-1, true));
        System.out.println(failures == 0 ? "LayerListCursorCheck: ok" : "LayerListCursorCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
