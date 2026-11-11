package org.helioviewer.jhv.gui.search;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.Action;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

/**
 * The demo scene (HS-10): a new session holding the bundled 2025-08-14 ~09:00 UTC frames of SDO/AIA
 * 171 and 193, Proba-3/ASPIICS, SOHO/LASCO C2 and C3 and PUNCH ({@link TourSamples#DEMO_NAMES}),
 * with RHEF on the AIA layer. Nothing to download, so it opens offline; it reads only files the jar carries.
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
        Display.setHelioradialPerObserver(false); // a PUNCH or Proba-3 frame at its own observer's angle overlaps the Earth-view ones badly
        List<File> files;
        try {
            files = TourSamples.extract(TourSamples.folder(), TourSamples.DEMO_NAMES);
        } catch (IOException | RuntimeException e) {
            Log.warn("The demo scene's sample could not be unpacked", e);
            return;
        }
        // one after another, so the layer order is the file order and the AIA layer is the master
        CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
        for (File f : files) {
            boolean aia = f == files.get(0);
            chain = chain.thenCompose(x -> Commands.loadImage(f.toURI()))
                    .thenAccept(layer -> {
                        if (aia)
                            EventQueue.invokeLater(() -> applyFilter(layer));
                    });
        }
        chain.exceptionally(e -> {
            Log.warn("The demo scene's samples did not all load", e);
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
