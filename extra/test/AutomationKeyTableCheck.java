package org.helioviewer.jhv.timelines;

import java.util.List;

import org.helioviewer.jhv.automation.Track;
import org.helioviewer.jhv.time.TimeUtils;

// The key table in an animated lane's options panel: typing a time, a value or an easing into a
// cell changes exactly that key of the track, by exactly that amount, and nothing else.
//
// Not checked: the Swing table itself (editors, focus, the combo box), and that an edit redraws
// the plot and the image; those are the onEdit callback, which this check only counts.
public final class AutomationKeyTableCheck {

    private static final long T0 = TimeUtils.parse("2026-10-08T12:00:00");
    private static int failures;
    private static int edits;

    public static void main(String[] args) {
        Track track = new Track("display.warpLambda");
        track.put(new Track.Key(T0, 0.5, Track.Interp.LINEAR));
        track.put(new Track.Key(T0 + 60_000, 1.5, Track.Interp.LINEAR));
        track.put(new Track.Key(T0 + 120_000, 2.5, Track.Interp.HOLD));
        AutomationKeyTableModel model = new AutomationKeyTableModel(track, () -> edits++);

        check(model.getRowCount() == 3, "one row per key");
        check("2026-10-08T12:01:00.000".equals(model.getValueAt(1, AutomationKeyTableModel.TIME)), "time shows as UTC to the millisecond");
        check(Double.valueOf(1.5).equals(model.getValueAt(1, AutomationKeyTableModel.VALUE)), "value shows as a number");

        model.setValueAt(0.75, 1, AutomationKeyTableModel.VALUE);
        check(track.getKeys().get(1).value() == 0.75, "a typed value lands on that key");
        check(track.getKeys().get(1).time() == T0 + 60_000, "and leaves its time alone");
        check(track.getKeys().get(0).value() == 0.5 && track.getKeys().get(2).value() == 2.5, "and leaves the other keys alone");
        check(edits == 1, "an edit reports itself once");

        model.setValueAt("2026-10-08T12:00:30.250", 1, AutomationKeyTableModel.TIME);
        check(track.getKeys().get(1).time() == T0 + 30_250, "a typed time lands exactly, not snapped");
        check(track.getKeys().get(1).value() == 0.75, "and keeps the key's value");

        model.setValueAt("2026-10-08 12:03", 1, AutomationKeyTableModel.TIME); // past the last key
        List<Track.Key> keys = track.getKeys();
        check(keys.getLast().time() == T0 + 180_000 && keys.getLast().value() == 0.75, "a time past a neighbour reorders the keys");
        check(model.getValueAt(2, AutomationKeyTableModel.TIME).equals("2026-10-08T12:03:00.000"), "and the rows follow");

        model.setValueAt(Track.Interp.SMOOTH, 0, AutomationKeyTableModel.EASING);
        check(track.getKeys().getFirst().interp() == Track.Interp.SMOOTH, "a chosen easing lands on that key");

        int before = edits;
        List<Track.Key> snapshot = track.getKeys();
        model.setValueAt("yesterday-ish", 0, AutomationKeyTableModel.TIME);
        model.setValueAt("", 0, AutomationKeyTableModel.TIME);
        model.setValueAt(Double.NaN, 0, AutomationKeyTableModel.VALUE);
        model.setValueAt("1.0", 0, AutomationKeyTableModel.VALUE); // not a Number: JTable's editor converts first
        check(track.getKeys().equals(snapshot), "nonsense changes nothing");
        check(edits == before, "and reports no edit");

        track.shiftValue(0, 9); // a drag in the plot
        check(model.refresh(), "refresh notices a change made in the plot");
        check(Double.valueOf(9).equals(model.getValueAt(0, AutomationKeyTableModel.VALUE)), "and shows it");
        check(!model.refresh(), "and reports nothing when nothing changed");

        // A key deleted in the plot while its cell is being edited: the edit must not land on a neighbour.
        List<Track.Key> shown = track.getKeys();
        track.removeKey(1);
        model.setValueAt(42.0, 1, AutomationKeyTableModel.VALUE);
        check(track.getKeys().stream().noneMatch(k -> k.value() == 42.0), "an edit to a key that is gone lands nowhere");
        check(model.getRowCount() == shown.size() - 1, "and the table catches up");

        check(AutomationKeyTableModel.parseTime("2026-10-08T12:00:00Z") == T0, "a trailing Z is accepted");
        check(AutomationKeyTableModel.parseTime("2026-10-08T12:00") == T0, "seconds may be left off");

        System.out.println(failures == 0 ? "AutomationKeyTableCheck: OK" : "AutomationKeyTableCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private AutomationKeyTableCheck() {}
}
