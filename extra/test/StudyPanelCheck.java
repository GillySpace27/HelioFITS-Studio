package org.helioviewer.jhv.gui.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.helioviewer.jhv.gui.component.StudyCells.Col;
import org.helioviewer.jhv.study.Study;
import org.json.JSONObject;

/**
 * The Study table model, headless: blank cells inherit and store nothing, typing overrides,
 * clearing returns to inherit, paste with a header line, and the reasons refused cells give.
 * No Settings, no display: the model takes the scene view and layers as arguments.
 */
public final class StudyPanelCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static int c(Col col) {
        return col.ordinal();
    }

    public static void main(String[] args) {
        JSONObject scene = new JSONObject("""
                {"projection":"Orthographic","warpLambda":0.5,"surfaceModel":"flat",
                 "camera":{"fov":0.017453292519943295,"zoom":2.0,"translation":[0,0],"dragRotation":[1,0,0,0]}}""");
        Map<String, Boolean> layers = new LinkedHashMap<>();
        layers.put("AIA 171", true);
        layers.put("LASCO C2", false);

        Study study = new Study();
        StudyTableModel m = new StudyTableModel(study, scene, layers);
        expect("columns in the spec's order", m.getColumnCount() == 10 && m.getColumnName(0).equals("Name")
                && m.getColumnName(1).equals("Projection") && m.getColumnName(6).equals("Rotation")
                && m.getColumnName(7).equals("Layers") && m.getColumnName(9).equals("Enabled"));
        expect("rotation is read only, the rest editable", !m.isCellEditable(0, c(Col.ROTATION)) && m.isCellEditable(0, c(Col.FOV)));

        // A blank row: everything inherits, nothing is stored.
        m.paste("Name\nplain");
        expect("a pasted name-only row is one row named plain", m.getRowCount() == 1 && m.variant(0).name().equals("plain"));
        expect("a blank row stores no override", m.variant(0).override().isEmpty());
        boolean allInherited = true;
        for (Col col : Col.values())
            if (col.inheritable())
                allInherited &= m.isInherited(0, c(col)) && "".equals(m.getValueAt(0, c(col)));
        expect("every inheritable cell of a blank row inherits and shows no value of its own", allInherited);
        expect("inherited projection shows the scene's", m.inheritedText(c(Col.PROJECTION)).equals("Orthographic"));
        expect("inherited lambda shows the scene's", m.inheritedText(c(Col.LAMBDA)).equals("0.5"));
        expect("inherited FOV shows the scene's in degrees", m.inheritedText(c(Col.FOV)).equals("1"));
        expect("inherited zoom and center", m.inheritedText(c(Col.ZOOM)).equals("2") && m.inheritedText(c(Col.CENTER)).equals("0, 0"));
        expect("inherited rotation is 0 degrees", m.inheritedText(c(Col.ROTATION)).equals("0°"));
        expect("inherited layers list the scene's", m.inheritedText(c(Col.LAYERS)).equals("AIA 171: on, LASCO C2: off"));
        expect("inherited size is the canvas without a stills size", m.inheritedText(c(Col.SIZE)).equals("canvas"));
        expect("the session study has the row", study.variants.size() == 1 && study.variants.get(0) == m.variant(0));

        // Typing overrides; only the typed key is stored.
        m.setValueAt("0", 0, c(Col.LAMBDA));
        expect("typed lambda is stored as warpLambda only", m.variant(0).override().similar(new JSONObject("{\"warpLambda\":0.0}")));
        expect("typed lambda is no longer inherited", !m.isInherited(0, c(Col.LAMBDA)) && m.getValueAt(0, c(Col.LAMBDA)).equals("0"));
        m.setValueAt("2", 0, c(Col.FOV));
        expect("FOV typed in degrees is stored in radians under camera",
                Math.abs(m.variant(0).override().getJSONObject("camera").getDouble("fov") - Math.toRadians(2)) < 1e-12
                        && m.variant(0).override().getJSONObject("camera").length() == 1);
        m.setValueAt(org.helioviewer.jhv.display.MapMode.HelioradialUnrolled, 0, c(Col.PROJECTION));
        expect("projection from the dropdown is stored by enum name", m.variant(0).override().getString("projection").equals("HelioradialUnrolled"));
        expect("projection shows its label", m.getValueAt(0, c(Col.PROJECTION)).equals("Helioradial Unrolled"));
        m.setValueAt("AIA 171: off", 0, c(Col.LAYERS));
        expect("layers chip text stores one layer", m.variant(0).override().getJSONObject("layers").similar(new JSONObject("{\"AIA 171\":false}")));
        m.setValueAt("1024 x 512", 0, c(Col.SIZE));
        expect("size stored as [w, h]", m.variant(0).override().getJSONArray("size").getInt(1) == 512);
        expect("the resolved row replaces only its keys", m.resolved(0).getString("surfaceModel").equals("flat")
                && m.resolved(0).getJSONObject("camera").getDouble("zoom") == 2.0 && m.resolved(0).getDouble("warpLambda") == 0.0);

        // Clearing returns to inherit and removes the key.
        m.setValueAt("", 0, c(Col.LAMBDA));
        expect("clearing lambda removes warpLambda", !m.variant(0).override().has("warpLambda") && m.isInherited(0, c(Col.LAMBDA)));
        m.setValueAt("inherit", 0, c(Col.FOV));
        expect("\"inherit\" clears FOV and drops the empty camera", !m.variant(0).override().has("camera") && m.isInherited(0, c(Col.FOV)));
        m.setValueAt("", 0, c(Col.PROJECTION));
        m.setValueAt(" ", 0, c(Col.LAYERS));
        m.setValueAt("", 0, c(Col.SIZE));
        expect("clearing every cell leaves an empty override", m.variant(0).override().isEmpty());

        // Validation reasons; a refused cell stores nothing and leaves the row out of an export.
        m.setValueAt("2", 0, c(Col.LAMBDA));
        expect("lambda 2 is refused with its range", "Lambda is a number from -1 to 1.".equals(m.reason(0, c(Col.LAMBDA))));
        expect("a refused cell keeps its text on screen but stores nothing",
                m.getValueAt(0, c(Col.LAMBDA)).equals("2") && !m.variant(0).override().has("warpLambda") && !m.isInherited(0, c(Col.LAMBDA)));
        expect("an invalid row is not exportable", m.exportable().isEmpty() && !m.rowValid(0));
        expect("the count line says the row is left out", m.countLine(1).equals("0 variants x 1 time = 0 PNGs; 1 row has an error and is left out"));
        m.setValueAt("200", 0, c(Col.FOV));
        expect("FOV over 120 degrees is refused", "FOV is in degrees, from 1/360 to 120.".equals(m.reason(0, c(Col.FOV))));
        m.setValueAt("-1", 0, c(Col.ZOOM));
        expect("zoom below 0 is refused", "Zoom is a number above 0.".equals(m.reason(0, c(Col.ZOOM))));
        m.setValueAt("3", 0, c(Col.CENTER));
        expect("one number for center is refused", "Center is two numbers: x, y.".equals(m.reason(0, c(Col.CENTER))));
        m.setValueAt("Mercator", 0, c(Col.PROJECTION));
        expect("an unknown projection is refused by name", String.valueOf(m.reason(0, c(Col.PROJECTION))).contains("\"Mercator\""));
        m.setValueAt("EIT 195: on", 0, c(Col.LAYERS));
        expect("a layer the scene lacks is refused", "No layer named \"EIT 195\" in this scene.".equals(m.reason(0, c(Col.LAYERS))));
        m.setValueAt("big", 0, c(Col.SIZE));
        expect("a size without width x height is refused", String.valueOf(m.reason(0, c(Col.SIZE))).startsWith("Size is width x height"));
        expect("firstReason names row and column", String.valueOf(m.firstReason(-1)).startsWith("Row 1, Projection: "));
        for (Col col : new Col[]{Col.LAMBDA, Col.FOV, Col.ZOOM, Col.CENTER, Col.PROJECTION, Col.LAYERS, Col.SIZE})
            m.setValueAt("", 0, c(col));
        expect("clearing refused cells makes the row valid again", m.rowValid(0) && m.exportable().size() == 1);
        m.setValueAt("", 0, c(Col.NAME));
        expect("a blank name is refused", String.valueOf(m.reason(0, c(Col.NAME))).startsWith("A name is needed"));
        m.setValueAt("plain", 0, c(Col.NAME));

        // Paste with a header line, columns in any order.
        List<String> problems = m.paste("""
                Lambda\tName\tProjection\tFOV\tRotation\tEnabled\tColour
                1\tlinear\tHelioradial\t\t12\tyes\tred
                -1\treciprocal\thelioradial unrolled\t0.5\t\toff\t
                9\tbad\t\t\t\t\t
                """);
        expect("paste appends three rows under the header", m.getRowCount() == 4 && m.variant(1).name().equals("linear")
                && m.variant(3).name().equals("bad"));
        expect("an unknown header column is reported", problems.size() == 1 && problems.get(0).contains("\"Colour\""));
        expect("pasted values land in the override", m.variant(1).override().similar(new JSONObject("{\"warpLambda\":1.0,\"projection\":\"Helioradial\"}")));
        expect("a blank pasted cell inherits", m.isInherited(1, c(Col.FOV)));
        expect("the read-only rotation column is skipped", !m.variant(1).override().has("camera"));
        expect("labels and case are accepted for projection, Enabled off is read",
                m.variant(2).override().getString("projection").equals("HelioradialUnrolled") && !m.variant(2).enabled());
        expect("a refused pasted cell shows red", m.reason(3, c(Col.LAMBDA)) != null && m.getValueAt(3, c(Col.LAMBDA)).equals("9"));
        expect("count line counts only valid enabled rows", m.countLine(1).equals("2 variants x 1 time = 2 PNGs; 1 row has an error and is left out"));
        expect("the export copy holds only the valid enabled rows", m.exportStudy().variants.size() == 2
                && m.exportStudy().variants.get(1).name().equals("linear") && study.variants.size() == 4);
        expect("a paste without a header is refused", m.paste("1\t2\t3\n4\t5\t6").get(0).startsWith("The first line must be a header"));
        expect("and adds no rows", m.getRowCount() == 4);

        // Duplicate names, move, duplicate, delete, save this view.
        m.setValueAt("linear", 3, c(Col.NAME));
        expect("a duplicate name is refused, naming the other row", String.valueOf(m.reason(3, c(Col.NAME))).startsWith("Row 2 has this name too"));
        m.delete(new int[]{3});
        int moved = m.move(2, 0);
        expect("move puts a row before another", moved == 0 && m.variant(0).name().equals("reciprocal") && study.variants.get(0).name().equals("reciprocal"));
        int d = m.duplicate(0);
        expect("duplicate copies the override under a new name", d == 1 && !m.variant(1).name().equals("reciprocal")
                && m.variant(1).override().similar(m.variant(0).override()));
        JSONObject view = new JSONObject(scene.toString()).put("projection", "Helioradial");
        int added = m.addFromView(view, layers);
        JSONObject o = m.variant(added).override();
        expect("save this view stores every value explicit", o.getString("projection").equals("Helioradial") && o.has("warpLambda")
                && o.getJSONObject("camera").length() == 4 && o.getJSONObject("layers").length() == 2 && !o.has("surfaceModel"));
        expect("and nothing in it inherits but size", m.isInherited(added, c(Col.SIZE)) && !m.isInherited(added, c(Col.ROTATION))
                && !m.isInherited(added, c(Col.LAYERS)));
        m.setValueAt("800x600", added, c(Col.SIZE));
        m.updateFromView(added, new JSONObject(scene.toString()), layers);
        expect("update row from view keeps size and takes the view", m.variant(added).override().getString("projection").equals("Orthographic")
                && m.variant(added).override().getJSONArray("size").getInt(0) == 800);

        if (failures > 0) {
            System.out.println("StudyPanelCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("StudyPanelCheck: all passed");
    }
}
