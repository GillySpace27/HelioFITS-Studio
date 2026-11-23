package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JCheckBox;

import org.helioviewer.jhv.layers.GridLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;

/**
 * The 3D Scene pane's planet controls drive the grid layer that is in the scene, including after a
 * session is opened. Layers.restore swaps in a new grid layer and announces only layersCleared; the
 * pane rebuilt its Planets section on layerAdded alone, so after any session load its Show box
 * toggled the discarded layer and the planets could not be turned on (2026-11-22).
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.selector.ScenePlanetsRestoreCheck
 */
public final class ScenePlanetsRestoreCheck {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** The planets' Show box: the first checkbox labelled Show after the Planets section's controls are built. */
    private static JCheckBox showBox(Container root) {
        List<JCheckBox> found = new ArrayList<>();
        collect(root, found);
        return found.isEmpty() ? null : found.getLast();
    }

    private static void collect(Container c, List<JCheckBox> out) {
        for (Component k : c.getComponents()) {
            if (k instanceof JCheckBox b && "Show".equals(b.getText()))
                out.add(b);
            if (k instanceof Container cc)
                collect(cc, out);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-planets").toString()); // never the real settings
        // The registry's null image layer reaches SPICE through its metadata (as LayersReorderCheck)
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        SceneSizePanel pane = new SceneSizePanel();

        GridLayer before = Layers.getGridLayer();
        JCheckBox show = showBox(pane);
        check(before != null && show != null, "the pane has a planets Show box for the scene's grid layer");
        if (show == null) {
            System.out.println("ScenePlanetsRestoreCheck: " + ++failures + " FAILURE(S)");
            System.exit(1);
        }
        boolean was = before.isShowPlanets();
        show.doClick();
        check(before.isShowPlanets() != was, "before a session load, Show toggles the scene's planets");

        // A session load: a fresh grid layer replaces the old one, and only layersCleared is said
        List<Layer> restored = new ArrayList<>();
        restored.add(new GridLayer(null));
        Layers.restore(restored);
        GridLayer after = Layers.getGridLayer();
        check(after != null && after != before, "the restore put a new grid layer in the scene");

        JCheckBox show2 = showBox(pane);
        boolean was2 = after.isShowPlanets();
        if (show2 != null)
            show2.doClick();
        check(show2 != null && after.isShowPlanets() != was2, "after a session load, Show toggles the new grid layer's planets");
        check(show2 != null && show2.isSelected() == after.isShowPlanets(), "and the box shows that layer's state");

        // A reorder says layersCleared too, with the same grid layer: rebuilding then closed a Planets
        // section the user had opened (Sourcery on #331), so the controls stay as they are
        Layers.fireLayersRearranged();
        check(showBox(pane) == show2, "a layer reorder keeps the same planet controls (an open section stays open)");

        System.out.println(failures == 0 ? "ScenePlanetsRestoreCheck: PASS" : "ScenePlanetsRestoreCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private ScenePlanetsRestoreCheck() {}
}
