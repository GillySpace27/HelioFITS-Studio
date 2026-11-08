package org.helioviewer.jhv.gui.dialog;

import java.awt.FileDialog;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;
import javax.swing.JOptionPane;
import javax.swing.Timer;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Session;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.movie.ExportReplay;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.movie.ProcessingRecord;
import org.helioviewer.jhv.thread.Task;

import org.json.JSONObject;

/**
 * Open an export from its {@code .hfrec} alone: the scene in the record goes to a new window (never over the one the
 * person is working in), where one picture is drawn again at the recorded size and compared with the one the record
 * describes, ending in a verdict: Identical, Equivalent, Different, or Cannot check. See {@link ExportReplay}.
 */
public final class ExportReplayWindow {

    /** System properties of a replay window: the record it replays, and whether the whole range was asked for. */
    static final String PROP = "jhv.replay", PROP_RANGE = "jhv.replayRange";

    private static final long SETTLE_MS = 3000; // the image is drawn once; decoding goes on a moment after the layer exists
    private static final long TIMEOUT_MS = 180_000;

    private ExportReplayWindow() {}

    /** Scene to a new window, from a record file, off the event thread. {@code range}: load the whole range of a movie. */
    public static void openInNewWindow(Path record, boolean range) {
        Task.submitBackground(() -> {
            JSONObject rec = ExportReplay.read(record);
            Path unpack = Path.of(Directories.CACHE.getPath(), "ReplayData", ProcessingRecord.exportName(record.getFileName().toString()));
            ExportReplay.Prepared p = ExportReplay.prepare(rec, record, range, unpack);
            File scene = Session.newWindowFile();
            Files.createDirectories(scene.toPath().getParent());
            Files.writeString(scene.toPath(), p.session().toString(1), StandardCharsets.UTF_8);
            return scene;
        }, scene -> Session.spawnWindow(scene, "-D" + PROP + "=" + record.toAbsolutePath(), "-D" + PROP_RANGE + "=" + range),
                t -> Message.err("Cannot open the record", String.valueOf(t.getMessage()), t));
    }

    /** File &gt; Open Export Record's Save Scene as .jhv: the scene the record carries, as a session file, whole range. */
    public static void saveScene(Path record, java.awt.Frame owner) {
        try {
            JSONObject rec = ExportReplay.read(record);
            JSONObject scene = ExportReplay.sessionOf(rec);
            if (scene == null) {
                Message.warn("No scene", "This record carries no scene, so there is nothing to save.");
                return;
            }
            FileDialog fd = new FileDialog(owner, "Save the scene as a session", FileDialog.SAVE);
            fd.setDirectory(record.toAbsolutePath().getParent().toString());
            fd.setFile(ProcessingRecord.exportName(record.getFileName().toString()) + ".jhv");
            fd.setVisible(true);
            File[] picked = fd.getFiles();
            if (picked.length == 0)
                return;
            String name = picked[0].getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jhv") ? picked[0].getPath() : picked[0].getPath() + ".jhv";
            Files.writeString(Path.of(name), scene.toString(1), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Message.err("Not saved", String.valueOf(e.getMessage()), e);
        }
    }

    /** In a window started by {@link #openInNewWindow}: once the scene is up, draw the picture again and show the verdict. */
    public static void startIfRequested() {
        String named = System.getProperty(PROP);
        if (named == null)
            return;
        Path record = Path.of(named);
        boolean range = Boolean.getBoolean(PROP_RANGE);
        Session.onNextStateLoad(ok -> {
            if (!ok) {
                Message.err("Replay", "The scene in " + record.getFileName() + " could not be loaded.");
                return;
            }
            long t0 = System.currentTimeMillis();
            long[] ready = {0};
            Timer timer = new Timer(500, null);
            timer.addActionListener(e -> {
                ImageLayer active = Layers.getActiveImageLayer();
                long now = System.currentTimeMillis();
                if (now - t0 > TIMEOUT_MS) {
                    timer.stop();
                    show(record, range, new ExportReplay.Outcome(ExportReplay.Verdict.CANNOT_CHECK, "Timed out waiting for the image."));
                } else if (active != null && active.hasPixels()) {
                    if (ready[0] == 0)
                        ready[0] = now;
                    else if (now - ready[0] >= SETTLE_MS) {
                        timer.stop();
                        draw(record, range);
                    }
                }
            });
            timer.start();
        });
    }

    private static void draw(Path record, boolean range) {
        JSONObject original;
        try {
            original = ExportReplay.read(record);
        } catch (IOException e) {
            show(record, range, new ExportReplay.Outcome(ExportReplay.Verdict.CANNOT_CHECK, e.getMessage()));
            return;
        }
        ImageLayer active = Layers.getActiveImageLayer();
        if (ExportReplay.isMovie(original) && !range && active != null) {
            Player.pause();
            Player.setFrame(active.getView().getMaximumFrameNumber()); // the frame asked for is the last one kept
        }
        JSONObject out = original.optJSONObject("output");
        int longSide = out == null ? 0 : Math.max(out.optInt("width"), out.optInt("height"));
        Commands.OperationContext context = new Commands.OperationContext(ExportReplayWindow.class, null, null, "replay.shot", (c, ok, message, output) -> {
            if (!ok || output == null) {
                show(record, range, new ExportReplay.Outcome(ExportReplay.Verdict.CANNOT_CHECK, "The picture could not be drawn again: " + message));
                return;
            }
            Task.submitBackground(() -> {
                Path drawn = Path.of(output.contains("%04d") ? String.format(output, 1) : output);
                Path side = ProcessingRecord.existingSidecar(drawn);
                if (side == null)
                    return new ExportReplay.Outcome(ExportReplay.Verdict.CANNOT_CHECK, "The picture was drawn but wrote no record to compare.");
                Path first = pictureBeside(record, original);
                // a movie's first frame cannot be read back as a PNG: only the digests can speak for it
                return ExportReplay.verdict(original, ExportReplay.read(side), first, drawn);
            }, o -> show(record, range, o),
                    t -> show(record, range, new ExportReplay.Outcome(ExportReplay.Verdict.CANNOT_CHECK, String.valueOf(t.getMessage()))));
        });
        ExportMovie.startSized(context, ViewState.RecordingMode.SHOT, longSide);
    }

    @Nullable
    private static Path pictureBeside(Path record, JSONObject original) {
        JSONObject out = original.optJSONObject("output");
        if (out == null || !"png".equalsIgnoreCase(out.optString("kind")) && !out.optString("media_type").equals("image/png"))
            return null;
        String name = out.optString("file_name");
        Path dir = record.toAbsolutePath().getParent();
        return name.isEmpty() || dir == null ? null : dir.resolve(name);
    }

    private static void show(Path record, boolean range, ExportReplay.Outcome o) {
        String title = switch (o.verdict()) {
            case IDENTICAL -> "Identical";
            case EQUIVALENT -> "Equivalent";
            case DIFFERENT -> "Different";
            case CANNOT_CHECK -> "Cannot check";
        };
        Log.info("Replay of " + record.getFileName() + ": " + title + ": " + o.why());
        boolean offerRange = !range && isMovieRecord(record);
        Object[] options = offerRange ? new Object[]{"Load whole range", "Close"} : new Object[]{"Close"};
        int choice = JOptionPane.showOptionDialog(MainFrame.get(), o.why(), "Replay of " + record.getFileName() + ": " + title,
                JOptionPane.DEFAULT_OPTION, o.verdict() == ExportReplay.Verdict.DIFFERENT ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE,
                null, options, options[options.length - 1]);
        if (offerRange && choice == 0)
            openInNewWindow(record, true);
    }

    private static boolean isMovieRecord(Path record) {
        try {
            return ExportReplay.isMovie(ExportReplay.read(record));
        } catch (IOException e) {
            return false;
        }
    }
}
