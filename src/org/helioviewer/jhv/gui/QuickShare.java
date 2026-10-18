package org.helioviewer.jhv.gui;

import java.io.File;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.movie.ExportPreset;

/**
 * File > Quick Share (HS-16): one loop of the movie, encoded with the "Share anywhere" preset, then shown in
 * the file manager. The preset and the loop mode apply to that one recording only (ExportMovie.startOnce):
 * the person's recording mode, size, format and preset are never changed, so nothing is restored afterwards.
 * The recording lands in Exports/ under its usual time-stamped name, so an earlier export is not overwritten.
 */
public final class QuickShare {

    static final String PRESET = "Share anywhere";

    public static void run() {
        ExportPreset preset = ExportPreset.byName(PRESET);
        if (preset == null) {
            Log.warn("Quick Share: no preset named " + PRESET);
            return;
        }
        Commands.OperationContext context = new Commands.OperationContext(QuickShare.class, null, null, "share.quick", (c, ok, message, output) -> {
            if (!ok || output == null) {
                Log.warn("Quick Share failed: " + message);
                return;
            }
            java.awt.EventQueue.invokeLater(() -> DesktopIntegration.reveal(new File(output)));
        });
        ExportMovie.startOnce(context, preset, ViewState.RecordingMode.LOOP);
    }

    private QuickShare() {}
}
