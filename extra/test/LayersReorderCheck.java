package org.helioviewer.jhv.layers.selector;

import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;

/**
 * The layer list is shown as three tables, and a dragged row has to land where it was dropped.
 *
 * <p>Two failures here are silent. A layer whose kind matches no table is simply absent from the
 * sidebar while still being rendered, and a layer in two tables gets two checkboxes for one state.
 * The other is arithmetic: a table row is a position in that table, while the registry interleaves
 * all three kinds, so dropping the first overlay past the last one has to land it immediately after
 * the last overlay and not simply at the end of the registry, which would jump it over rows of the
 * other kinds and silently change what draws on top of what.
 *
 * <p>Run: java -cp bin:extra/test-classes:resources org.helioviewer.jhv.layers.selector.LayersReorderCheck
 */
public final class LayersReorderCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        // The registry's null image layer reaches SPICE through its metadata, so the native has to
        // be loaded before Layers is touched at all.
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        LayersTableModel images = new LayersTableModel(Layer.Kind.IMAGE);
        LayersTableModel overlays = new LayersTableModel(Layer.Kind.OVERLAY);
        LayersTableModel viewpoint = new LayersTableModel(Layer.Kind.VIEWPOINT);

        List<Layer> all = Layers.getLayers();
        expect(!all.isEmpty(), "the default layers were never installed, so this check proves nothing");
        expect(images.getRowCount() + overlays.getRowCount() + viewpoint.getRowCount() == all.size(),
                "the three tables together hold every layer exactly once");
        // One camera row, not two: Free, Follow, Turntable and Overview are behaviours of the
        // single Viewpoint layer, and a second row is what let two of them run at once.
        expect(overlays.getRowCount() >= 4 && viewpoint.getRowCount() == 1,
                "expected the default overlays and one camera row, got "
                        + overlays.getRowCount() + " and " + viewpoint.getRowCount());

        for (Layer layer : all) {
            int seen = (holds(images, layer) ? 1 : 0) + (holds(overlays, layer) ? 1 : 0) + (holds(viewpoint, layer) ? 1 : 0);
            expect(seen == 1, layer.getName() + " reaches " + seen + " tables, not one");
            // Layers.add and Layers.reorder band the list by instanceof while the tables read
            // kind(). If those two ever disagree, a layer lands outside its own band and the
            // (ImageLayer) casts against imageLayersCount start reading the wrong objects.
            expect((layer.kind() == Layer.Kind.IMAGE) == layer instanceof ImageLayer,
                    layer.getName() + ": kind() and instanceof ImageLayer disagree");
        }

        // Drag the second overlay down two places. The overlays reorder and nothing else moves.
        List<Layer> before = new ArrayList<>(all);
        Layer moved = rowsOf(overlays).get(1);
        List<Layer> otherKindsBefore = otherThan(before, Layer.Kind.OVERLAY);
        overlays.reorder(1, 4);
        List<Layer> after = rowsOf(overlays);
        expect(after.indexOf(moved) == 3, moved.getName() + " landed at " + after.indexOf(moved) + ", not 3");
        expect(otherKindsBefore.equals(otherThan(Layers.getLayers(), Layer.Kind.OVERLAY)),
                "reordering an overlay disturbed the other kinds");
        expect(new java.util.HashSet<>(after).equals(new java.util.HashSet<>(rowsOf(overlays))) && after.size() == before.size() - otherKindsBefore.size(),
                "reordering lost or duplicated an overlay");

        // Drop past the last row. It must land immediately after the last overlay in the registry,
        // which is where the last overlay row is; landing anywhere else means the table row was
        // used as a registry index.
        Layer first = rowsOf(overlays).getFirst();
        Layer lastOverlay = rowsOf(overlays).getLast();
        List<Layer> otherKinds = otherThan(Layers.getLayers(), Layer.Kind.OVERLAY);
        overlays.reorder(0, overlays.getRowCount());
        expect(rowsOf(overlays).getLast() == first, first.getName() + " did not land last among the overlays");
        List<Layer> registry = Layers.getLayers();
        expect(registry.indexOf(first) == registry.indexOf(lastOverlay) + 1,
                first.getName() + " landed at registry index " + registry.indexOf(first)
                        + ", not immediately after the last overlay at " + registry.indexOf(lastOverlay));
        expect(otherKinds.equals(otherThan(registry, Layer.Kind.OVERLAY)),
                "the drop disturbed the rows of the other kinds");

        if (failures != 0)
            throw new AssertionError(failures + " layer-reorder failure(s)");
        System.out.println("LayersReorderCheck: PASS (" + images.getRowCount() + " image, "
                + overlays.getRowCount() + " overlay, " + viewpoint.getRowCount() + " viewpoint)");
        System.exit(0); // the registry starts worker threads; nothing here waits on them
    }

    private static List<Layer> rowsOf(LayersTableModel model) {
        List<Layer> rows = new ArrayList<>(model.getRowCount());
        for (int row = 0; row < model.getRowCount(); row++)
            rows.add((Layer) model.getValueAt(row, 0));
        return rows;
    }

    private static List<Layer> otherThan(List<Layer> layers, Layer.Kind kind) {
        return layers.stream().filter(layer -> layer.kind() != kind).toList();
    }

    private static boolean holds(LayersTableModel model, Layer layer) {
        return rowsOf(model).contains(layer);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private LayersReorderCheck() {}

}
