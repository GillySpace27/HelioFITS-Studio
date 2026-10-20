package org.helioviewer.jhv.movie;

import java.nio.file.Files;
import java.util.List;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.gui.component.MoviePanel;

/**
 * The presets a person hands to someone else, and the settings a fresh install records with, have
 * to come out as a file the other person's player opens: QuickTime, a browser, PowerPoint or
 * Keynote, the Windows default player. That is H.264 High profile, 4:2:0 at 8 bits, a frame no
 * larger than Level 5.1 allows (so a hardware decoder accepts it), even dimensions, the moov atom
 * at the front, in an MP4. Every one of those is a flag on the ffmpeg command line and nothing at
 * runtime notices when one goes missing: the encode succeeds and the recipient's player shows
 * nothing, which is the report that prompted this check (Gilly, 2026-10-20).
 *
 * <p>The archival rungs are held to the opposite promise: they keep their exactness, and their
 * description says they need VLC or ffmpeg, so nobody picks one for a slide deck by accident.
 *
 * <p>Run: java -cp bin:extra/test-classes:resources org.helioviewer.jhv.movie.ExportCompatibilityCheck
 */
public final class ExportCompatibilityCheck {

    /** Level 5.1's frame size, 36864 macroblocks of 16x16: the largest H.264 frame hardware decoders are expected to take. */
    private static final int LEVEL_51_PIXELS = 36864 * 256;

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // A throwaway home: the fresh-install defaults are what an absent settings file yields,
        // and a check must never read, let alone write, Gilly's real one.
        System.setProperty("user.home", Files.createTempDirectory("hfs-compat").toString());

        ExportFormat format = MoviePanel.storedFormat();
        ExportFormat.Chroma chroma = MoviePanel.storedChroma();
        ExportFormat.Depth depth = MoviePanel.storedDepth();
        expect("fresh install records H.264", format == ExportFormat.H264);
        expect("fresh install records 4:2:0 at 8 bits", chroma == ExportFormat.Chroma.YUV420 && depth == ExportFormat.Depth.EIGHT);
        expect("fresh install does not record an archival format", !format.isLossless());
        compatible("fresh install", format, chroma, depth, MoviePanel.isAllIntra());

        for (String name : List.of("Presentation", "Share anywhere")) {
            ExportPreset p = ExportPreset.byName(name);
            expect(name + " exists", p != null);
            if (p == null)
                continue;
            expect(name + " is H.264 (the codec every player and slide deck decodes)",
                    p.format() == ExportFormat.H264 || p.format() == ExportFormat.H264HQ);
            expect(name + " is 4:2:0 at 8 bits", p.chroma() == ExportFormat.Chroma.YUV420 && p.depth() == ExportFormat.Depth.EIGHT);
            expect(name + " is not all-intra (a small file is part of being sendable)", !p.allIntra());
            compatible(name, p.format(), p.chroma(), p.depth(), p.allIntra());
        }

        // The rungs that trade playability for exactness say so where the person is choosing.
        for (String name : List.of("Archive (exact)", "Dome projection"))  {
            ExportPreset p = ExportPreset.byName(name);
            expect(name + " says it needs VLC", p != null && p.description().contains("VLC"));
            expect(name + " is not what a fresh install records", p == null || p.format() != format || p.chroma() != chroma || p.depth() != depth);
        }

        // Quick Share is the one-click path, so it is held to HD: every phone, chat preview and
        // slide deck is happy at 1920, and the person's own size is left alone.
        ViewState.RecordingData share = ExportMovie.shareData(
                new ViewState.RecordingData(ViewState.RecordingMode.FREE, ViewState.RecordingAspect.SQUARE, 4096),
                ViewState.RecordingMode.LOOP);
        expect("Quick Share loops", share.mode() == ViewState.RecordingMode.LOOP);
        expect("Quick Share caps the long side at 1920 (got " + share.longSide() + ")", share.longSide() == 1920);
        ViewState.RecordingData small = ExportMovie.shareData(
                new ViewState.RecordingData(ViewState.RecordingMode.FREE, ViewState.RecordingAspect.WIDE, 1024),
                ViewState.RecordingMode.LOOP);
        expect("Quick Share leaves a smaller size alone", small.longSide() == 1024 && small.aspect() == ViewState.RecordingAspect.WIDE);

        System.out.println(failures == 0 ? "ExportCompatibilityCheck: ok" : "ExportCompatibilityCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The command-line properties a universally playable H.264 MP4 needs, at a common size and at one past Level 5.1. */
    private static void compatible(String who, ExportFormat format, ExportFormat.Chroma chroma, ExportFormat.Depth depth, boolean allIntra) {
        expect(who + ": lands in an .mp4", ".mp4".equals(format.extension));

        List<String> hd = ExportWriter.encodeArgs(format, chroma, depth, 1920, 1080, 20, allIntra);
        String joined = String.join(" ", hd);
        expect(who + ": encodes with libx264", joined.contains("-c:v libx264"));
        expect(who + ": High profile", joined.contains("-profile:v high ") || joined.endsWith("-profile:v high"));
        expect(who + ": yuv420p", joined.contains("-pix_fmt yuv420p ") || joined.endsWith("-pix_fmt yuv420p"));
        expect(who + ": +faststart", joined.contains("-movflags +faststart"));
        // Full-range (yuvj420p) H.264 is the one tag a share of players apply inconsistently; limited
        // range is what every camera writes and every decoder assumes.
        expect(who + ": limited range", joined.contains("-color_range 1") && !joined.contains("fullrange=on"));
        expect(who + ": bt709 tagged", joined.contains("-colorspace bt709") && joined.contains("-color_primaries bt709"));
        expect(who + ": no scaling needed at 1920x1080", !joined.contains("scale="));
        expect(who + ": keyframes at most half a second apart", gop(hd) >= 1 && gop(hd) <= 10);

        // 4096x4096 is Level 6.0 as encoded: software decoders manage, hardware ones refuse. It has
        // to come down to Level 5.1's frame size, keeping its shape, on even dimensions.
        List<String> big = ExportWriter.encodeArgs(format, chroma, depth, 4096, 4096, 20, allIntra);
        int[] scaled = scaleOf(big);
        expect(who + ": a 4096x4096 frame is scaled down (args: " + String.join(" ", big) + ")", scaled != null);
        if (scaled != null) {
            expect(who + ": scaled frame fits Level 5.1 (" + scaled[0] + "x" + scaled[1] + ")", (long) scaled[0] * scaled[1] <= LEVEL_51_PIXELS);
            expect(who + ": scaled frame has even sides", scaled[0] % 2 == 0 && scaled[1] % 2 == 0);
            expect(who + ": scaled frame keeps its shape", scaled[0] == scaled[1]);
            expect(who + ": scaled frame is not needlessly small", (long) scaled[0] * scaled[1] > LEVEL_51_PIXELS * 0.95);
        }
        int[] wide = scaleOf(ExportWriter.encodeArgs(format, chroma, depth, 5120, 2880, 20, allIntra));
        expect(who + ": a 5120x2880 frame keeps 16:9 when scaled", wide != null && Math.abs(wide[0] / (double) wide[1] - 16 / 9.0) < 0.01);
        expect(who + ": 3840x2160 is left at its size (Level 5.1 holds it)",
                scaleOf(ExportWriter.encodeArgs(format, chroma, depth, 3840, 2160, 20, allIntra)) == null);
    }

    private static int gop(List<String> args) {
        int i = args.indexOf("-g");
        return i < 0 ? -1 : Integer.parseInt(args.get(i + 1));
    }

    /** The WxH named by a "-vf scale=W:H..." argument, or null when the frame is passed through at its size. */
    private static int[] scaleOf(List<String> args) {
        for (String a : args) {
            if (!a.startsWith("scale="))
                continue;
            String[] parts = a.substring("scale=".length()).split(":");
            try {
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            } catch (NumberFormatException e) {
                return null; // a scale that names no size (the HDR colour-matrix one) is not a resize
            }
        }
        return null;
    }

}
