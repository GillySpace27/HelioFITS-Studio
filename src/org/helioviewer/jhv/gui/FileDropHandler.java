package org.helioviewer.jhv.gui;

import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.ExtensionFileFilter;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.Provenance;
import org.helioviewer.jhv.plugins.pointcloud.PointCloudLayer;

import org.json.JSONObject;

/**
 * Files dropped anywhere on the window load as what they are: point-cloud JSON into one Point
 * Cloud layer (several at once become its time series, matching the File-menu action), imagery
 * into image layers, a .jhv into a state load. A PNG this program exported carries its scene
 * (Provenance), and dropping it offers to reopen that scene instead. A folder loads the image
 * files directly inside it as one layer, as choosing them all in File > Open does. Anything not
 * recognized is named in a message, not only in the log: a drop that did nothing visible read
 * as a broken drop (Gilly, 2026-10-06).
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
        List<String> skipped = new ArrayList<>();
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            JSONObject scene = n.endsWith(".png") ? sceneOf(f) : null;
            if (scene != null)
                SwingUtilities.invokeLater(() -> offerScene(f, scene)); // after the drop completes
            else if (f.isDirectory()) {
                List<File> inside = folderImages(f);
                if (inside.isEmpty())
                    skipped.add(f.getName() + " (a folder with no image files directly inside)");
                else
                    SwingUtilities.invokeLater(() -> offerFolder(f, inside));
            } else if (n.endsWith(".json") || n.endsWith(".json.gz"))
                clouds.add(f);
            else if (n.endsWith(".jhv") || org.helioviewer.jhv.app.state.SessionArchive.isArchiveName(n))
                Commands.loadState(f.toURI()); // a .data.zip carries its session inside it
            else if (ExtensionFileFilter.isImage(n))
                Commands.loadImage(f.toURI());
            else {
                Log.warn("Dropped file not recognized: " + f.getName());
                skipped.add(f.getName());
            }
        }
        if (!clouds.isEmpty()) {
            PointCloudLayer layer = new PointCloudLayer(null);
            Layers.add(layer);
            clouds.forEach(f -> layer.load(f.toURI())); // all into the one layer, a time series
        }
        e.dropComplete(true);
        if (!skipped.isEmpty())
            SwingUtilities.invokeLater(() -> Message.warn("Not Opened", skippedMessage(skipped)));
    }

    /** Folders larger than this ask first: each frame's header is read before anything shows. */
    static final int ASK_ABOVE = 200;

    /** The image files directly inside a folder, by name; not its subfolders. */
    static List<File> folderImages(File dir) {
        File[] all = dir.listFiles(f -> f.isFile() && ExtensionFileFilter.isImage(f.getName()));
        if (all == null)
            return List.of();
        List<File> out = new ArrayList<>(java.util.Arrays.asList(all));
        out.sort(java.util.Comparator.comparing(File::getName));
        return out;
    }

    /** What the skipped-files message says. Pure, for the check. */
    static String skippedMessage(List<String> names) {
        int shown = Math.min(names.size(), 8);
        StringBuilder text = new StringBuilder(names.size() == 1 ? "This was not opened:\n" : "These were not opened:\n");
        for (int i = 0; i < shown; i++)
            text.append("    ").append(names.get(i)).append('\n');
        if (names.size() > shown)
            text.append("    and ").append(names.size() - shown).append(" more\n");
        return text.append("\nDrop FITS (.fits, .fit, .fts, .fz, also gzipped), JPEG 2000 (.jp2, .jpx), PNG, JPEG\n")
                .append("or ZIP images, a folder of them, a .jhv session, or point-cloud .json.").toString();
    }

    private static void offerFolder(File dir, List<File> inside) {
        if (inside.size() > ASK_ABOVE) {
            int choice = JOptionPane.showConfirmDialog(MainFrame.get(),
                    "Open the " + inside.size() + " image files in " + dir.getName() + " as one layer?",
                    "Open Folder", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION)
                return;
        }
        Commands.loadImage(inside.stream().map(File::toURI).toList());
    }

    /** The scene an exported PNG carries, with the home directory put back, or null for any other PNG. */
    @Nullable
    private static JSONObject sceneOf(File png) {
        try {
            JSONObject provenance = Provenance.readPngChunk(png.toPath());
            return provenance == null ? null : Provenance.scene(provenance);
        } catch (IOException | RuntimeException e) { // unreadable: load it as a picture, as before
            Log.warn("No scene read from " + png.getName(), e);
            return null;
        }
    }

    /**
     * Open the scene, or load the picture as before. The scene is written to a new session file
     * beside the others and opened from there, exactly as a dropped .jhv is: nothing existing is
     * overwritten, and the window's current session file is left as it was.
     */
    private static void offerScene(File png, JSONObject scene) {
        Object[] options = {"Open Scene", "Load as Image"};
        int choice = JOptionPane.showOptionDialog(MainFrame.get(),
                png.getName() + " was exported by HelioFITS Studio and carries the scene it was made from.\n"
                        + "Open Scene saves that scene as a new session file and opens it.",
                "Exported Scene", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == 1) {
            Commands.loadImage(png.toURI());
            return;
        }
        if (choice != 0)
            return; // dialog closed: do nothing
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File file = new File(Directories.STATES.getFile(), png.getName().replaceFirst("(?i)\\.png$", "") + "-scene-" + stamp + ".jhv");
        try {
            Files.writeString(file.toPath(), scene.toString(), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            Log.error("Could not save the scene from " + png.getName(), e);
            Message.err("Exported Scene", "Could not save the scene: " + e.getMessage());
            return;
        }
        Commands.loadState(file.toURI());
    }

    private FileDropHandler() {
    }

}
