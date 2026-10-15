package org.helioviewer.jhv.gui.search;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.Action;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

/**
 * The demo scene (HS-10): a new session holding the bundled SDO/AIA 171 sample (the one the tour
 * loads, {@link TourSamples}) with RHEF on. Nothing to download, so it opens offline. The PUNCH and
 * LASCO scenes the plan names wait for archive scenes that have been checked to load; this one
 * reads only a file the jar carries.
 */
public final class DemoScene {

    /** The filter the demo turns on. */
    static final ImageFilter.Type FILTER = ImageFilter.Type.RHEF;

    /** Start New Session (which asks first when there is work to lose), then load the sample with RHEF. */
    public static void open() {
        ActionCatalog.Entry fresh = ActionCatalog.get("newSession");
        if (fresh == null || MainFrame.get() == null)
            return;
        fresh.action().actionPerformed(new ActionEvent(MainFrame.get(), ActionEvent.ACTION_PERFORMED, "newSession"));
        if (!Layers.getImageLayers().isEmpty())
            return; // the user chose to keep the session
        List<File> files;
        try {
            files = TourSamples.extract(TourSamples.folder());
        } catch (IOException | RuntimeException e) {
            Log.warn("The demo scene's sample could not be unpacked", e);
            return;
        }
        for (File f : files)
            Commands.loadImage(f.toURI())
                    .thenAccept(layer -> EventQueue.invokeLater(() -> applyFilter(layer)))
                    .exceptionally(e -> {
                        Log.warn("The demo scene's sample did not load", e);
                        return null;
                    });
    }

    /** As choosing the filter in the layer's Filter list does, for this one layer. */
    static void applyFilter(@Nullable ImageLayer layer) {
        if (layer == null)
            return;
        layer.setFilter(FILTER);
        Layers.fireLayerUpdated(layer); // the Filters palette and the Image Layers row re-sync on it
        DisplayController.render(1);
    }

    /** Help > Open Demo Scene. */
    public static Action action() {
        return new AbstractAction("Open Demo Scene") {
            @Override
            public void actionPerformed(ActionEvent e) {
                open();
            }
        };
    }

    private DemoScene() {}

}
