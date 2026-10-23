package org.helioviewer.jhv.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.swing.Timer;

import org.helioviewer.jhv.io.CommandLine;
import org.helioviewer.jhv.layers.Layers;

/**
 * -export png -out file: the command line as a thin client of Commands. Once the scene has an image
 * (from -state or -load) it asks for a Screenshot, the same stamped PNG the Movie panel writes, copies
 * it to -out and ends the process. It ends it with Runtime.halt, so no shutdown hook runs and nothing
 * is autosaved; Session.init is never called in this mode, so no autosave timer exists either.
 */
public final class CliExport {

    private static final int POLL_MS = 500;
    private static final long DEFAULT_SETTLE_MS = 3000; // the image is drawn once; decoding continues a moment after the layer exists
    private static final long TIMEOUT_MS = 180_000;

    private static final java.util.concurrent.atomic.AtomicBoolean stateFailed = new java.util.concurrent.atomic.AtomicBoolean();

    public static void run() {
        Session.onNextStateLoad(ok -> { if (!ok) stateFailed.set(true); });
        long t0 = System.currentTimeMillis();
        long settle = Long.getLong("jhv.exportSettleMs", DEFAULT_SETTLE_MS);
        long[] ready = {0};
        Timer timer = new Timer(POLL_MS, null);
        timer.addActionListener(e -> {
            long now = System.currentTimeMillis();
            if (stateFailed.get())
                end(timer, CommandLine.Exit.INPUT_NOT_FOUND, "The state file could not be loaded.");
            else if (CommandLine.datasetFailed())
                end(timer, CommandLine.Exit.INPUT_NOT_FOUND, "The -dataset could not be loaded (see the log).");
            else if (now - t0 > TIMEOUT_MS)
                end(timer, CommandLine.Exit.EXPORT_FAILED, "Timed out waiting for an image.");
            else if (Layers.getActiveImageLayer() != null) {
                if (ready[0] == 0)
                    ready[0] = now;
                else if (now - ready[0] >= settle) {
                    timer.stop();
                    shoot();
                }
            }
        });
        timer.start();
    }

    private static void shoot() {
        Commands.OperationContext context = new Commands.OperationContext(CliExport.class, null, null, "export.png", (c, ok, message, output) -> {
            if (!ok || output == null) {
                fail(CommandLine.Exit.EXPORT_FAILED, message);
                return;
            }
            try {
                Path out = Path.of(CommandLine.exportOut());
                if (out.toAbsolutePath().getParent() != null)
                    Files.createDirectories(out.toAbsolutePath().getParent());
                Files.copy(Path.of(output.contains("%04d") ? String.format(output, 1) : output), out, // a series reports its ffmpeg pattern; a screenshot is frame 1
                         StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException ex) {
                fail(CommandLine.Exit.EXPORT_FAILED, "Could not write " + CommandLine.exportOut() + ": " + ex.getMessage());
                return;
            }
            Log.info("Exported " + CommandLine.exportOut());
            halt(CommandLine.Exit.OK);
        });
        Commands.recordStart(context, new Commands.RecordStartInput("SHOT", null, null, null, null));
    }

    private static void end(Timer timer, CommandLine.Exit exit, String message) {
        timer.stop();
        fail(exit, message);
    }

    private static void fail(CommandLine.Exit exit, String message) {
        Log.error("Export failed: " + message);
        CommandLine.emit(System.err, "export failed: " + message);
        halt(exit);
    }

    private static void halt(CommandLine.Exit exit) {
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(exit.code);
    }

    private CliExport() {}
}
