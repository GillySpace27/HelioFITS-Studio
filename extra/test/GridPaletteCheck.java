package org.helioviewer.jhv.layers.selector;

import org.helioviewer.jhv.layers.GridLayer;
import org.helioviewer.jhv.layers.Layers;

/**
 * Two claims the Grid palette rests on, neither obvious from reading the code alone.
 *
 * <p>First, that {@link Layers#getGridLayer()} actually finds the one default {@link GridLayer}
 * the app always creates, the same way {@code getViewpointLayer()} and its siblings do. Second,
 * and the one worth a real run rather than an inspection: that {@link GridLayerOptions} tolerates
 * being constructed a SECOND time against the same layer. The palette is the only home of the
 * panel now (the sidebar row points at it), but it rebuilds the panel whenever the grid layer is
 * replaced by a session restore, so two constructions in one process are the normal case. If a
 * static or a listener were hiding in one of the section builders, the second construction is
 * where it would surface: a duplicate registration, a stale reference, or a NullPointerException
 * from state the first instance already claimed.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.selector.GridPaletteCheck
 */
public final class GridPaletteCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // Loading Layers at all runs its default-layer construction, which reaches Sun.getEarth()
        // and so needs SPICE; see the same boilerplate in FourierPreviewCheck.
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        GridLayer layer = Layers.getGridLayer();
        expect("Layers.getGridLayer() finds the default layer", layer != null);

        if (layer != null) {
            GridLayerOptions rowPanel = new GridLayerOptions(layer);
            expect("a first GridLayerOptions builds against the default layer", rowPanel != null);

            GridLayerOptions palettePanel = new GridLayerOptions(layer);
            expect("a second GridLayerOptions against the SAME layer also builds, with no exception",
                    palettePanel != null && palettePanel != rowPanel);

            // Not the same widgets, so setting one does not silently move the other's slider.
            expect("the two instances are independent components", rowPanel.getComponentCount() > 0
                    && palettePanel.getComponentCount() > 0 && !java.util.Arrays.asList(rowPanel.getComponents()).equals(java.util.Arrays.asList(palettePanel.getComponents())));
        }

        System.out.println(failures == 0 ? "GridPaletteCheck: PASS" : "GridPaletteCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private GridPaletteCheck() {}

}
