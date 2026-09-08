package org.helioviewer.jhv.gui.component;

import java.nio.file.Files;
import java.nio.file.Path;

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
 * <p>The second rule is the mode-driven visibility, and the thing worth pinning is not what
 * showsEncoding returns but that it still agrees with ExportMovie. Screenshot mode hides the preset
 * row and the disclosure, which is only honest because ExportMovie.startRecording hands a snapshot
 * to ExportWriter as a fixed 16-bit RGB PNG and ignores the video format entirely. That branch is
 * private and sits behind a GLGrab, so it cannot be called from a headless check; its source can be
 * read, and that is what the second half does. Assertions on showsEncoding alone would only restate
 * its own one-line body and would still pass on the day a screenshot starts honouring the format.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.component.EncodingSummaryCheck
 * [path to ExportMovie.java] -- the optional path is there so a mutated copy can be checked without
 * editing the real file.
 */
public final class EncodingSummaryCheck {

    private static final String DEFAULT_SOURCE = "src/org/helioviewer/jhv/movie/ExportMovie.java";
    private static final String SHOT_BRANCH = "if (mode == ViewState.RecordingMode.SHOT) {";

    private static int failures;

    public static void main(String[] args) throws Exception {
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

        checkModeVisibilityMatchesExporter(Path.of(args.length > 0 ? args[0] : DEFAULT_SOURCE));

        System.out.println(failures == 0 ? "EncodingSummaryCheck: PASS" : "EncodingSummaryCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    /**
     * What each mode actually hands the writer, read out of ExportMovie, against what the pane
     * hides for that mode.
     */
    private static void checkModeVisibilityMatchesExporter(Path source) throws Exception {
        if (!Files.isReadable(source)) {
            System.out.println("FAIL: cannot read " + source.toAbsolutePath() + " (run from the repository root)");
            failures++;
            return;
        }
        String src = Files.readString(source);

        int marker = src.indexOf(SHOT_BRANCH);
        if (marker < 0) {
            System.out.println("FAIL: " + source + " no longer contains \"" + SHOT_BRANCH + "\"; "
                    + "the snapshot branch moved and this check has to follow it");
            failures++;
            return;
        }
        int shotOpen = src.indexOf('{', marker);
        int shotClose = matchingBrace(src, shotOpen);
        int elseOpen = src.indexOf('{', shotClose);
        int elseClose = matchingBrace(src, elseOpen);
        if (shotClose < 0 || elseOpen < 0 || elseClose < 0 || !src.substring(shotClose, elseOpen).contains("else")) {
            System.out.println("FAIL: the snapshot branch in " + source + " is no longer a plain if/else");
            failures++;
            return;
        }

        String shotArgs = exportWriterArguments(src.substring(shotOpen, shotClose), "the snapshot branch");
        String videoArgs = exportWriterArguments(src.substring(elseOpen, elseClose), "the video branch");
        if (shotArgs == null || videoArgs == null)
            return;

        // A snapshot is fixed exactly when the writer gets the PNG triple by name and none of the
        // three locals the video settings were read into.
        boolean snapshotFixed = shotArgs.contains("ExportFormat.PNG")
                && shotArgs.contains("ExportFormat.Chroma.RGB")
                && shotArgs.contains("ExportFormat.Depth.SIXTEEN")
                && !mentionsVideoSettings(shotArgs);
        boolean videoHonoursSettings = mentionsVideoSettings(videoArgs);

        expect(MoviePanel.showsEncoding(ViewState.RecordingMode.SHOT) != snapshotFixed,
                snapshotFixed
                        ? "a screenshot is still a fixed PNG, so the pane must hide the encoding rows"
                        : "the snapshot branch no longer writes a fixed PNG (" + shotArgs.strip()
                                + "), so the encoding rows have to come back for Screenshot");
        expect(MoviePanel.showsEncoding(ViewState.RecordingMode.LOOP) == videoHonoursSettings,
                "one loop encodes with the format, chroma and depth the rows set (" + videoArgs.strip() + ')');
        expect(MoviePanel.showsEncoding(ViewState.RecordingMode.FREE) == videoHonoursSettings,
                "unlimited encodes with the format, chroma and depth the rows set (" + videoArgs.strip() + ')');
    }

    /** Whether an argument list passes on the locals ExportMovie read the video settings into. */
    private static boolean mentionsVideoSettings(String arguments) {
        return arguments.matches("(?s).*\\bformat\\b.*")
                && arguments.matches("(?s).*\\bchroma\\b.*")
                && arguments.matches("(?s).*\\bdepth\\b.*");
    }

    /** The parenthesised argument list of the branch's {@code new ExportWriter(...)}, or null. */
    private static String exportWriterArguments(String branch, String what) {
        int call = branch.indexOf("new ExportWriter(");
        if (call < 0) {
            System.out.println("FAIL: " + what + " no longer constructs an ExportWriter");
            failures++;
            return null;
        }
        int open = branch.indexOf('(', call);
        int depth = 0;
        for (int i = open; i < branch.length(); i++) {
            char c = branch.charAt(i);
            if (c == '(')
                depth++;
            else if (c == ')' && --depth == 0)
                return branch.substring(open + 1, i);
        }
        System.out.println("FAIL: " + what + ": the ExportWriter call is unbalanced");
        failures++;
        return null;
    }

    /** Index of the brace closing the one at {@code open}, or -1. Comments and strings are not nested braces here. */
    private static int matchingBrace(String src, int open) {
        if (open < 0)
            return -1;
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{')
                depth++;
            else if (c == '}' && --depth == 0)
                return i;
        }
        return -1;
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
