package org.helioviewer.jhv.gui.search;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.layers.Layers;

/**
 * Sample images the Getting Started tour loads when it starts on an empty canvas (PUNCH team,
 * 2026-10-07), so its steps about the layer list, colour tables and levels point at a real layer
 * instead of an empty panel. In the spirit of sunpy's sample data: small files bundled with the
 * program, real data with real headers, not for science. Each file, where it came from and the
 * terms it is shared under are in resources/samples/README.md.
 *
 * <p>The reader wants a file, so each sample is copied out of the jar once, into Samples in the
 * HFStudio folder, where a session that keeps the layer can find it again on the next launch.
 */
final class TourSamples {

    /** In load order: the first becomes the master layer. */
    static final List<String> NAMES = List.of("aia_171_20120831T173411_1024.fits");

    static File folder() {
        return new File(Directories.HOME.getFile(), "Samples");
    }

    /** Copy each bundled sample into {@code dir} unless a complete copy is already there; returns the files. */
    static List<File> extract(File dir) throws IOException {
        Files.createDirectories(dir.toPath());
        List<File> files = new ArrayList<>(NAMES.size());
        for (String name : NAMES) {
            File file = new File(dir, name);
            if (!file.isFile() || file.length() == 0) {
                File part = new File(dir, name + ".part"); // never a half-written sample under the real name
                try (InputStream is = FileUtils.getResource("/samples/" + name)) {
                    Files.copy(is, part.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            files.add(file);
        }
        return files;
    }

    /** On the EDT: load the samples when no image layer is loaded. Never fails the tour. */
    static void loadIfEmpty() {
        if (!Layers.getImageLayers().isEmpty())
            return;
        try {
            for (File f : extract(folder()))
                Commands.loadImage(f.toURI()); // one layer each: a list of URIs would be one layer's frames
        } catch (IOException | RuntimeException e) {
            Log.warn("Tour sample images could not be loaded; the tour runs on the empty canvas", e);
        }
    }

    private TourSamples() {}

}
