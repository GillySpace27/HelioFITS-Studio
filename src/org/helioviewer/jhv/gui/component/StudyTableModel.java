package org.helioviewer.jhv.gui.component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.table.AbstractTableModel;

import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.gui.component.StudyCells.Col;
import org.helioviewer.jhv.gui.component.StudyCells.Parsed;
import org.helioviewer.jhv.study.Study;
import org.helioviewer.jhv.study.Variant;
import org.json.JSONObject;

/**
 * The Study table: one row per {@link Variant} of a {@link Study}, written back to
 * {@code study.variants} after every change. A blank cell inherits from the scene view and stores
 * nothing; typed text that does not parse is held here, beside the row, never in the variant, and
 * marks the row invalid so it is left out of an export.
 */
@SuppressWarnings("serial")
public final class StudyTableModel extends AbstractTableModel {

    private static final Col[] COLS = Col.values();

    private static final class Row {
        Variant v;
        final Map<Col, String> bad = new EnumMap<>(Col.class);

        Row(Variant v) {
            this.v = v;
        }
    }

    private final Study study;
    private final List<Row> rows = new ArrayList<>();
    private JSONObject scene;
    private Map<String, Boolean> layers;

    /**
     * @param sceneView what blank cells inherit: Commands.getView of the scene
     * @param layers    the scene's image layers by name, with whether each is on
     */
    public StudyTableModel(Study study, JSONObject sceneView, Map<String, Boolean> layers) {
        this.study = study;
        scene = new JSONObject(sceneView.toString());
        this.layers = new LinkedHashMap<>(layers);
        for (Variant v : study.variants)
            rows.add(new Row(v));
    }

    public Study study() {
        return study;
    }

    public void setScene(JSONObject sceneView, Map<String, Boolean> sceneLayers) {
        scene = new JSONObject(sceneView.toString());
        layers = new LinkedHashMap<>(sceneLayers);
        if (!rows.isEmpty())
            fireTableRowsUpdated(0, rows.size() - 1); // not DataChanged, which would drop the selection
    }

    public JSONObject scene() {
        return new JSONObject(scene.toString());
    }

    public List<String> layerNames() {
        return new ArrayList<>(layers.keySet());
    }

    private void sync() {
        study.variants.clear();
        for (Row r : rows)
            study.variants.add(r.v);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLS.length;
    }

    @Override
    public String getColumnName(int col) {
        return COLS[col].header;
    }

    public static Col col(int col) {
        return COLS[col];
    }

    @Override
    public Class<?> getColumnClass(int col) {
        return COLS[col] == Col.ENABLED ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        return COLS[col] != Col.ROTATION;
    }

    public Variant variant(int row) {
        return rows.get(row).v;
    }

    /** What the cell shows as its own value: the refused text, the override, or "" when it inherits. */
    @Override
    public Object getValueAt(int row, int col) {
        Row r = rows.get(row);
        Col c = COLS[col];
        String bad = r.bad.get(c);
        if (bad != null)
            return bad;
        if (c == Col.NAME)
            return r.v.name();
        if (c == Col.ENABLED)
            return r.v.enabled();
        String s = StudyCells.format(c, r.v.override());
        return s == null ? "" : s;
    }

    public boolean isInherited(int row, int col) {
        Row r = rows.get(row);
        Col c = COLS[col];
        return c.inheritable() && !r.bad.containsKey(c) && StudyCells.format(c, r.v.override()) == null;
    }

    /** The grey text of a blank cell: the scene's value for that column. */
    public String inheritedText(int col) {
        Col c = COLS[col];
        if (c == Col.LAYERS)
            return layers.isEmpty() ? "as in scene" : StudyCells.formatLayers(layers);
        if (c == Col.SIZE) {
            JSONObject stills = study.output().stills();
            String s = stills == null ? null : StudyCells.format(Col.SIZE, stills);
            return s == null ? "canvas" : s;
        }
        String s = StudyCells.format(c, scene);
        return s == null ? "" : s;
    }

    /** Why the cell is refused, or null. */
    @Nullable
    public String reason(int row, int col) {
        Row r = rows.get(row);
        Col c = COLS[col];
        String bad = r.bad.get(c);
        if (bad != null)
            return StudyCells.parse(c, bad, layers.keySet()).reason();
        if (c == Col.NAME) {
            String name = r.v.name();
            if (name.isBlank())
                return StudyCells.parse(c, name, layers.keySet()).reason();
            for (int i = 0; i < rows.size(); i++)
                if (i != row && rows.get(i).v.name().equals(name))
                    return "Row " + (i + 1) + " has this name too; their files would have the same name.";
        }
        return null;
    }

    public boolean rowValid(int row) {
        for (int col = 0; col < COLS.length; col++)
            if (reason(row, col) != null)
                return false;
        return true;
    }

    /** "Row 2, FOV: ..." for the first refused cell of a row, or of the table when row is -1; null when none. */
    @Nullable
    public String firstReason(int row) {
        for (int i = row < 0 ? 0 : row; i < (row < 0 ? rows.size() : row + 1); i++)
            for (int col = 0; col < COLS.length; col++) {
                String why = reason(i, col);
                if (why != null)
                    return "Row " + (i + 1) + ", " + COLS[col].header + ": " + why;
            }
        return null;
    }

    @Override
    public void setValueAt(Object value, int row, int col) {
        Row r = rows.get(row);
        Col c = COLS[col];
        if (c == Col.ENABLED) {
            r.v.setEnabled(Boolean.TRUE.equals(value));
            r.bad.remove(c);
        } else if (c == Col.NAME) {
            String s = value == null ? "" : value.toString();
            r.v.setName(s.strip());
            if (s.isBlank())
                r.bad.put(c, "");
            else
                r.bad.remove(c);
        } else
            setText(r, c, value instanceof MapMode m ? m.name() : value == null ? "" : value.toString());
        sync();
        fireTableRowsUpdated(0, rows.size() - 1); // a name change can fix or break another row's name
    }

    private void setText(Row r, Col c, String text) {
        Parsed p = StudyCells.parse(c, text, layers.keySet());
        JSONObject o = r.v.override();
        StudyCells.put(o, c, p.value()); // refused or blank: the key goes, so nothing stale is exported
        if (p.reason() != null)
            r.bad.put(c, text);
        else
            r.bad.remove(c);
        r.v = new Variant(r.v.name(), r.v.enabled(), o);
    }

    /** The full view a row previews: the scene with the row's keys replaced. */
    public JSONObject resolved(int row) {
        return rows.get(row).v.resolve(scene);
    }

    /** Every value the table shows, explicit, from a view (Commands.getView) and the layers' on/off state. */
    static JSONObject explicit(JSONObject view, Map<String, Boolean> layerStates, JSONObject keep) {
        JSONObject o = new JSONObject(keep.toString());
        if (view.has("projection"))
            o.put("projection", view.get("projection"));
        if (view.has("warpLambda"))
            o.put("warpLambda", view.get("warpLambda"));
        JSONObject cam = view.optJSONObject("camera");
        if (cam != null) {
            JSONObject c = new JSONObject();
            for (String k : new String[]{"fov", "zoom", "translation", "dragRotation"})
                if (cam.has(k))
                    c.put(k, cam.get(k));
            o.put("camera", c);
        }
        if (!layerStates.isEmpty())
            o.put("layers", new JSONObject(layerStates));
        return o;
    }

    private String freeName(String base) {
        for (int n = rows.size() + 1; ; n++) {
            String name = base + " " + n;
            boolean used = false;
            for (Row r : rows)
                used |= r.v.name().equals(name);
            if (!used)
                return name;
        }
    }

    /** Save this view: a new last row with every value explicit. Returns its index. */
    public int addFromView(JSONObject view, Map<String, Boolean> layerStates) {
        rows.add(new Row(new Variant(freeName("view"), true, explicit(view, layerStates, new JSONObject()))));
        sync();
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        return rows.size() - 1;
    }

    /** Update row from view: the view's values made explicit; size and keys the table does not show are kept. */
    public void updateFromView(int row, JSONObject view, Map<String, Boolean> layerStates) {
        Row r = rows.get(row);
        r.v = new Variant(r.v.name(), r.v.enabled(), explicit(view, layerStates, r.v.override()));
        for (Col c : new Col[]{Col.PROJECTION, Col.LAMBDA, Col.FOV, Col.ZOOM, Col.CENTER, Col.LAYERS})
            r.bad.remove(c);
        sync();
        fireTableRowsUpdated(row, row);
    }

    public int duplicate(int row) {
        Row src = rows.get(row);
        Row copy = new Row(new Variant(freeName(src.v.name().isBlank() ? "copy" : src.v.name()), src.v.enabled(), src.v.override()));
        copy.bad.putAll(src.bad);
        rows.add(row + 1, copy);
        sync();
        fireTableRowsInserted(row + 1, row + 1);
        return row + 1;
    }

    /** Removes rows by index, any order. */
    public void delete(int[] which) {
        int[] s = which.clone();
        java.util.Arrays.sort(s);
        for (int i = s.length - 1; i >= 0; i--)
            rows.remove(s[i]);
        sync();
        fireTableDataChanged();
    }

    /** Moves one row so it lands before what is now at index {@code to} (0..size). Returns its new index. */
    public int move(int from, int to) {
        Row r = rows.remove(from);
        int dest = to > from ? to - 1 : to;
        rows.add(dest, r);
        sync();
        fireTableRowsUpdated(Math.min(from, dest), Math.max(from, dest));
        return dest;
    }

    /** Appends pasted rows, refused cells kept as text so they show red. Returns the problems to report. */
    public List<String> paste(String text) {
        StudyCells.Paste p = StudyCells.parsePaste(text, layers.keySet(), rows.size() + 1);
        if (!p.rows().isEmpty()) {
            int first = rows.size();
            for (StudyCells.PastedRow pr : p.rows()) {
                Row r = new Row(new Variant(pr.name(), pr.enabled(), pr.override()));
                r.bad.putAll(pr.bad());
                rows.add(r);
            }
            sync();
            fireTableRowsInserted(first, rows.size() - 1);
        }
        return p.problems();
    }

    /** Rows that are enabled and have no refused cell: what Export set writes. */
    public List<Variant> exportable() {
        List<Variant> l = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).v.enabled() && rowValid(i))
                l.add(rows.get(i).v);
        return l;
    }

    /** A copy of the study holding only the exportable rows, for the export hook. */
    public Study exportStudy() {
        Study s = Study.fromJson(study.toJson());
        s.variants.clear();
        for (Variant v : exportable())
            s.variants.add(new Variant(v.name(), true, v.override()));
        return s;
    }

    /** "4 variants x 1 time = 4 PNGs", and how many rows an error leaves out. */
    public String countLine(int times) {
        int n = exportable().size();
        int left = 0;
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).v.enabled() && !rowValid(i))
                left++;
        String s = n + (n == 1 ? " variant" : " variants") + " x " + times + (times == 1 ? " time" : " times")
                + " = " + n * times + (n * times == 1 ? " PNG" : " PNGs");
        if (left > 0)
            s += "; " + left + (left == 1 ? " row has an error and is left out" : " rows have an error and are left out");
        return s;
    }
}
