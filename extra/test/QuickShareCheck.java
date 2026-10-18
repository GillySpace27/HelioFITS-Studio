package org.helioviewer.jhv.movie;

import java.nio.file.Files;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.ViewState;

/**
 * Quick Share applies its preset and loop mode to one recording only: encodingFor(preset) is the preset's
 * encoding, encodingFor(null) is still the person's stored settings, and building the one-shot changes
 * neither the stored settings nor the recording data.
 */
public final class QuickShareCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-quickshare").toString()); // never the real settings
        Settings.setProperty("video.format", "FFV1");
        Settings.setProperty("video.chroma", "RGB");
        Settings.setProperty("video.depth", "SIXTEEN");
        Settings.setProperty("video.allIntra", "true");
        ViewState.RecordingData before = ViewState.recordingData();

        ExportPreset share = ExportPreset.byName("Share anywhere");
        expect("the Share anywhere preset exists", share != null);
        ExportMovie.Encoding once = ExportMovie.encodingFor(share);
        expect("the one-shot encoding is the preset's: H264, 4:2:0, 8 bit, not all-intra",
                once.format() == ExportFormat.H264 && once.chroma() == ExportFormat.Chroma.YUV420
                        && once.depth() == ExportFormat.Depth.EIGHT && !once.allIntra());

        ViewState.RecordingData loop = ExportMovie.withMode(before, ViewState.RecordingMode.LOOP);
        expect("the one-shot data is a loop at the person's size", loop.mode() == ViewState.RecordingMode.LOOP
                && loop.aspect() == before.aspect() && loop.longSide() == before.longSide());

        ExportMovie.Encoding stored = ExportMovie.encodingFor(null);
        expect("without a one-shot the person's own settings are used",
                stored.format() == ExportFormat.FFV1 && stored.chroma() == ExportFormat.Chroma.RGB
                        && stored.depth() == ExportFormat.Depth.SIXTEEN && stored.allIntra());
        expect("the stored format is untouched", "FFV1".equals(Settings.getProperty("video.format")));
        expect("the recording data is untouched", ViewState.recordingData().equals(before));

        System.out.println(failures == 0 ? "QuickShareCheck: ok" : "QuickShareCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
