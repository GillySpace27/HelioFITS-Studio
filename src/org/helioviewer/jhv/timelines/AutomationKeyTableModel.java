package org.helioviewer.jhv.timelines;

import java.time.format.DateTimeParseException;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import org.helioviewer.jhv.automation.Track;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * The keys of one {@link Track} as rows of numbers: time, value and easing, each editable in place.
 *
 * <p>The lane's drag gestures are quick and imprecise by nature; this is the precise half. A
 * typed time is taken exactly as typed, not snapped to a frame, because a number someone typed
 * is the number they meant. Free of the renderer and the plot, so it can be exercised headlessly
 * (extra/test/AutomationKeyTableCheck.java); whatever has to happen after an edit (redrawing the
 * lane, re-rendering the image) is the {@code onEdit} the panel hands in.
 */
@SuppressWarnings("serial")
final class AutomationKeyTableModel extends AbstractTableModel {

    static final int TIME = 0, VALUE = 1, EASING = 2;
    private static final String[] NAMES = {"Time (UTC)", "Value", "Easing"};

    private final Track track;
    private final Runnable onEdit;
    private List<Track.Key> shown; // what the rows currently show, to notice edits made elsewhere

    AutomationKeyTableModel(Track _track, Runnable _onEdit) {
        track = _track;
        onEdit = _onEdit;
        shown = track.getKeys();
    }

    /** Re-reads the track if a drag, a double-click or "Flatten" changed it. Returns whether it had. */
    boolean refresh() {
        List<Track.Key> keys = track.getKeys();
        if (keys.equals(shown))
            return false;
        shown = keys;
        fireTableDataChanged();
        return true;
    }

    Track.Key keyAt(int row) {
        return shown.get(row);
    }

    @Override
    public int getRowCount() {
        return shown.size();
    }

    @Override
    public int getColumnCount() {
        return NAMES.length;
    }

    @Override
    public String getColumnName(int column) {
        return NAMES[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return switch (column) {
            case VALUE -> Double.class; // JTable's number editor: a non-number is refused in the cell, not here
            case EASING -> Track.Interp.class;
            default -> String.class;
        };
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return true;
    }

    @Override
    public Object getValueAt(int row, int column) {
        Track.Key k = shown.get(row);
        return switch (column) {
            case TIME -> TimeUtils.format(k.time());
            case VALUE -> k.value();
            default -> k.interp();
        };
    }

    @Override
    public void setValueAt(Object aValue, int row, int column) {
        if (row < 0 || row >= shown.size())
            return;
        // The track's index, not the row: the two agree because both are sorted by time, and
        // refresh() runs before any edit can start, but a key that vanished under the editor
        // (deleted by a double-click in the plot mid-edit) must not take a neighbour with it.
        int index = track.getKeys().indexOf(shown.get(row));
        if (index < 0) {
            refresh();
            return;
        }
        switch (column) {
            case TIME -> {
                Long t = parseTime(aValue);
                if (t == null)
                    return; // not a time; the cell shows the old one again
                track.moveKey(index, t, shown.get(row).value());
            }
            case VALUE -> {
                if (!(aValue instanceof Number n) || !Double.isFinite(n.doubleValue()))
                    return;
                track.shiftValue(index, n.doubleValue());
            }
            case EASING -> {
                if (!(aValue instanceof Track.Interp interp))
                    return;
                track.setInterp(index, interp);
            }
            default -> {
                return;
            }
        }
        refresh(); // a new time can reorder the rows
        onEdit.run();
    }

    /**
     * UTC as the cell prints it ({@code 2026-10-08T12:34:56.789}); a space for the T, and the
     * seconds or the milliseconds left off, are accepted too. Null when it is not a time.
     */
    static Long parseTime(Object o) {
        if (o == null)
            return null;
        String s = o.toString().trim().replace(' ', 'T');
        if (s.endsWith("Z"))
            s = s.substring(0, s.length() - 1);
        if (s.isEmpty())
            return null;
        try {
            return TimeUtils.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

}
