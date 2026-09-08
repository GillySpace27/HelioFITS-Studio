package org.helioviewer.jhv.gui.component;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.movie.ExportFormat;

/**
 * The two rules that decide what the Playback and Recording pane is allowed to hide.
 *
 * <p>Format, colour and depth now sit behind a collapsed disclosure, and ExportMovie reads those
 * from Settings rather than from the widgets. A hidden format still governs the recording, so the
 * disclosure's header has to say what it is hiding. The header is built by splitting
 * ExportFormat.toString at its double space, which is exactly the kind of thing that rots without
 * anyone noticing: change that separator and the header silently becomes either the whole combo
 * entry ("H.264  .mp4  lossy") or a truncated fragment, and the collapsed section starts hiding a
 * decision instead of summarising it.
 *
 * <p>The second rule is the mode-driven visibility. The preset row and the disclosure are hidden
 * in Screenshot mode, and that is only honest because ExportMovie.start writes a snapshot as a
 * 16-bit RGB PNG whatever the video settings say. If a screenshot ever starts honouring the video
 * format, this check fails and the row has to come back.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.component.EncodingSummaryCheck
 */
public final class EncodingSummaryCheck {

    private static int failures;

    public static void main(String[] args) {
        // The summary is name, sampling, depth, and the keyframe flag when it means anything.
        equal(MoviePanel.encodingSummary(ExportFormat.H264, ExportFormat.Chroma.YUV420, ExportFormat.Depth.EIGHT, false),
                "H.264, 4:2:0, 8-bit", "H.264 at the defaults");
        equal(MoviePanel.encodingSummary(ExportFormat.H264, ExportFormat.Chroma.YUV420, ExportFormat.Depth.EIGHT, true),
                "H.264, 4:2:0, 8-bit, all-I", "the keyframe choice shows when it applies");
        equal(MoviePanel.encodingSummary(ExportFormat.H265_HLG, ExportFormat.Chroma.YUV444, ExportFormat.Depth.TEN, true),
                "H.265 HDR (HLG), 4:4:4, 10-bit, all-I", "an HDR name keeps its curve");
        equal(MoviePanel.encodingSummary(ExportFormat.FFV1, ExportFormat.Chroma.RGB, ExportFormat.Depth.SIXTEEN, true),
                "FFV1, RGB, 16-bit", "FFV1 is all-intra by definition, so it does not say so");
        // A series carries exactly one sampling and no keyframe choice, so naming either says nothing.
        equal(MoviePanel.encodingSummary(ExportFormat.EXR, ExportFormat.Chroma.RGB, ExportFormat.Depth.SIXTEEN, true),
                "EXR series (layered, half float), 16-bit", "a series names only what it is");
        equal(MoviePanel.encodingSummary(ExportFormat.PNG, ExportFormat.Chroma.RGB, ExportFormat.Depth.SIXTEEN, false),
                "PNG series, 16-bit", "the PNG series");

        // Whatever the format list grows to, the header must stay a header: the name only, with
        // none of the extension-and-lossiness tail that belongs in the combo.
        for (ExportFormat format : ExportFormat.values()) {
            String summary = MoviePanel.encodingSummary(format, format.clamp(ExportFormat.Chroma.YUV420),
                    format.clamp(ExportFormat.Chroma.YUV420, ExportFormat.Depth.EIGHT), true);
            expect(!summary.contains("  "), format.name() + ": the name was not split off (" + summary + ')');
            expect(!summary.contains("lossy") && !summary.contains("lossless"),
                    format.name() + ": lossiness leaked into the header (" + summary + ')');
            expect(!summary.contains(".mp4") && !summary.contains(".mkv") && !summary.contains("per frame"),
                    format.name() + ": the extension leaked into the header (" + summary + ')');
            expect(format.toString().startsWith(summary.split(",")[0]),
                    format.name() + ": the header does not name the format (" + summary + ')');
        }

        // Screenshot writes a PNG whatever the video settings say, so the encoding rows describe
        // nothing it will do; the other two modes encode exactly what those rows say.
        expect(!MoviePanel.showsEncoding(ViewState.RecordingMode.SHOT), "a screenshot hides the encoding rows");
        expect(MoviePanel.showsEncoding(ViewState.RecordingMode.LOOP), "one loop shows the encoding rows");
        expect(MoviePanel.showsEncoding(ViewState.RecordingMode.FREE), "unlimited shows the encoding rows");

        System.out.println(failures == 0 ? "EncodingSummaryCheck: PASS" : "EncodingSummaryCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void equal(String got, String want, String what) {
        if (!want.equals(got)) {
            System.out.println("FAIL: " + what + " -- got \"" + got + "\", want \"" + want + '"');
            failures++;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private EncodingSummaryCheck() {}
}
