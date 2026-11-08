package org.helioviewer.jhv.gui.status;

import java.util.List;

import org.helioviewer.jhv.gui.status.ActivityRows.Row;

/**
 * The Task Manager's "Right now" list is rebuilt every 500 ms; a row without a layer (the recording)
 * must keep its selection across rebuilds, so Stop still acts on it.
 */
public final class ActivityRowsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        Row before = new Row("Recording", "Recording movie: frame 3", -1, false, null);
        List<Row> after = List.of(new Row("Events", "2 downloads", -1, false, null),
                new Row("Recording", "Recording movie: frame 4 of 90", -1, false, null));
        expect("a layerless row is found again after its detail text changed", ActivityRows.indexOf(after, ActivityRows.key(before)) == 1);
        expect("a row that has finished is not found", ActivityRows.indexOf(List.of(after.get(0)), ActivityRows.key(before)) == -1);
        expect("no key finds nothing", ActivityRows.indexOf(after, null) == -1);
        if (failures > 0)
            System.exit(1);
    }
}
