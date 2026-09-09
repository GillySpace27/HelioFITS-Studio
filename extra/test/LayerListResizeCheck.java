package org.helioviewer.jhv.layers.selector;

/**
 * The layer list's resize handle cannot be dragged into a crash.
 *
 * <p>The height is clamped between two rows and exactly all rows, the second so the scrollbar
 * vanishes once every layer fits. Those two cross: with one layer, or none, all-rows is SHORTER
 * than the two-row floor, and Math.clamp throws when the bounds cross rather than picking one. So
 * a session with a single layer crashed on the first pixel of any drag of the handle,
 * IllegalArgumentException 41 &gt; 21, which is a 20 pixel row height.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.layers.selector.LayerListResizeCheck
 */
public final class LayerListResizeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final int RH = 20;
    private static final int FLOOR = RH * 2 + 1; // 41

    public static void main(String[] args) {
        // The reported crash, exactly: one row, 20 pixel rows, any drag at all.
        expect("one layer does not throw, and holds the floor",
                LayersPanel.dragHeight(41, -5, RH, 1) == FLOOR);
        expect("an empty list does not throw either",
                LayersPanel.dragHeight(41, 30, RH, 0) == FLOOR);
        expect("neither does a negative row count, whatever produced it",
                LayersPanel.dragHeight(41, 0, RH, -3) == FLOOR);

        // With room to move, the clamp is the clamp it always was.
        int ceiling = RH * 6 + 1;
        expect("a drag downward grows the list", LayersPanel.dragHeight(FLOOR, 40, RH, 6) == FLOOR + 40);
        expect("and stops when every row fits", LayersPanel.dragHeight(FLOOR, 999, RH, 6) == ceiling);
        expect("a drag upward shrinks it", LayersPanel.dragHeight(ceiling, -40, RH, 6) == ceiling - 40);
        expect("and stops at two rows", LayersPanel.dragHeight(ceiling, -999, RH, 6) == FLOOR);

        // A tall enough start with too few rows is pulled back to the floor rather than left above
        // a ceiling that is under it: the floor wins, which is the collapse the floor exists to stop.
        expect("a list already taller than one row comes back to the floor",
                LayersPanel.dragHeight(400, 0, RH, 1) == FLOOR);

        for (int rows = 0; rows < 40; rows++)
            for (int dy = -600; dy <= 600; dy += 37) {
                int h = LayersPanel.dragHeight(120, dy, RH, rows);
                if (h < FLOOR) {
                    expect("rows=" + rows + " dy=" + dy + " never goes under the floor", false);
                    rows = 40;
                    break;
                }
            }
        expect("no row count and no drag distance can go under the floor", failures == 0);

        System.out.println(failures == 0 ? "LayerListResizeCheck: PASS" : "LayerListResizeCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private LayerListResizeCheck() {}

}
