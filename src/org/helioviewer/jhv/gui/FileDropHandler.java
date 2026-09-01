package org.helioviewer.jhv.gui;

import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.plugins.pointcloud.PointCloudLayer;

/**
 * Files dropped anywhere on the window load as what they are: point-cloud JSON into one Point
 * Cloud layer (several at once become its time series, matching the File-menu action), imagery
 * into image layers, a .jhv into a state load. Anything unrecognized is named in a log line
 * rather than silently swallowed.
 */
final class FileDropHandler extends DropTargetAdapter {

    /** DropTargets are one per component, so each component gets its own wrapper. */
    static void attach(Component... components) {
        for (Component c : components)
            new DropTarget(c, new FileDropHandler());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void drop(DropTargetDropEvent e) {
        e.acceptDrop(DnDConstants.ACTION_COPY);
        List<File> files;
        try {
            files = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
        } catch (Exception ex) {
            e.dropComplete(false);
            return;
        }

        List<File> clouds = new ArrayList<>();
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".json") || n.endsWith(".json.gz"))
                clouds.add(f);
            else if (n.endsWith(".jhv"))
                Commands.loadState(f.toURI());
            else if (n.endsWith(".fits") || n.endsWith(".fts") || n.endsWith(".fits.gz") || n.endsWith(".jp2")
                    || n.endsWith(".jpx") || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg"))
                Commands.loadImage(f.toURI());
            else
                Log.warn("Dropped file not recognized: " + f.getName());
        }
        if (!clouds.isEmpty()) {
            PointCloudLayer layer = new PointCloudLayer(null);
            Layers.add(layer);
            clouds.forEach(f -> layer.load(f.toURI())); // all into the one layer, a time series
        }
        e.dropComplete(true);
    }

    private FileDropHandler() {
    }

}
