package org.helioviewer.jhv.movie;

import java.util.List;

import org.helioviewer.jhv.movie.ExportFormat.Chroma;
import org.helioviewer.jhv.movie.ExportFormat.Depth;

/**
 * The export table's axes have to agree with each other and with what this build's encoders can
 * actually accept, and nothing at runtime notices when they do not.
 *
 * <p>Two failures here are silent rather than loud. A deep pixel format with a shallow grab
 * produces a file whose container correctly announces Main 10 while the picture inside was
 * quantized to 256 levels before the encoder ever saw it. And a second -x265-params on the command
 * line does not merge with the first, it replaces it, so the 4:4:4 fix would quietly take the
 * colour signalling with it. Both look like success everywhere except in the pixels.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.movie.ExportFormatCheck
 */
public final class ExportFormatCheck {

    private static int failures;

    public static void main(String[] args) {
        for (ExportFormat f : ExportFormat.values()) {
            List<Chroma> chromas = f.chromas();
            expect(!chromas.isEmpty(), f + ": offers no colour format at all");

            for (Chroma c : Chroma.values()) {
                List<Depth> depths = f.depths(c);
                expect(chromas.contains(c) == !depths.isEmpty(),
                        f + "/" + c + ": listed as available but has no depths, or vice versa");

                for (Depth d : depths) {
                    // -pix_fmt must come from ONE place. Listing it in the settings too leaves
                    // which one wins to ffmpeg's argument order.
                    expect(!f.settings(c, d).contains("-pix_fmt"), f + ": -pix_fmt belongs in pixFmt");

                    // Exactly one private-parameter string per command, or the last silently wins.
                    expect(count(f.settings(c, d), "-x264-params") <= 1, f + "/" + c + ": duplicate -x264-params");
                    expect(count(f.settings(c, d), "-x265-params") <= 1, f + "/" + c + ": duplicate -x265-params");

                    String pix = f.pixFmt(c, d);
                    if (f.isSeries()) {
                        expect(pix == null, f + ": a series must let ffmpeg choose its pixel format");
                        continue;
                    }
                    expect(pix != null, f + "/" + c + "/" + d + ": no pixel format");
                    expect(depthOf(pix) == d.bits,
                            f + "/" + c + "/" + d + ": pixFmt " + pix + " is not " + d.bits + "-bit");
                    // The grab has to be deep whenever the output is, or the extra bits are empty.
                    expect(f.wantsHighBitDepth(d) == (d != Depth.EIGHT),
                            f + "/" + d + ": wantsHighBitDepth disagrees with the depth");
                }
            }

            // Whatever is stored, the clamp has to land on something this codec really supports.
            for (Chroma c : Chroma.values())
                for (Depth d : Depth.values()) {
                    Chroma cc = f.clamp(c);
                    expect(f.supports(cc, f.clamp(c, d)), f + ": clamp(" + c + ", " + d + ") is unsupported");
                }
        }

        // Capabilities read off this build's encoders. If ffmpeg is ever swapped for one built
        // differently, these are the claims that go stale first.
        expect(!ExportFormat.H264.supports(Chroma.RGB, Depth.EIGHT), "libx264 has no planar RGB here");
        expect(!ExportFormat.H264.supports(Chroma.YUV420, Depth.TWELVE), "libx264 has no 12-bit here");
        expect(ExportFormat.H265.supports(Chroma.YUV444, Depth.TWELVE), "libx265 does 12-bit 4:4:4");
        expect(!ExportFormat.H265.supports(Chroma.YUV420, Depth.SIXTEEN), "x265 implements no 16-bit");
        expect(ExportFormat.FFV1.supports(Chroma.RGB, Depth.SIXTEEN), "FFV1 does 16-bit RGB");
        expect(!ExportFormat.FFV1.supports(Chroma.RGB, Depth.EIGHT), "FFV1 has no 8-bit planar RGB");

        // The lossless path stays in RGB rather than going through the colour matrix. Verified
        // outside this check by round-tripping a capture back to raw: byte for byte identical.
        expect("gbrp16le".equals(ExportFormat.FFV1.pixFmt(Chroma.RGB, Depth.SIXTEEN)),
                "FFV1 RGB 16-bit must be gbrp16le");

        // Naming a YUV colourspace alongside an RGB pixel format makes ffmpeg insert a conversion
        // to satisfy it, and the "RGB" export silently comes out as yuv444p. This is the assertion
        // that caught that: it is invisible in the file's own metadata, which reports success.
        for (ExportFormat f : List.of(ExportFormat.H265, ExportFormat.H265HQ, ExportFormat.FFV1)) {
            List<String> rgb = f.settings(Chroma.RGB, Depth.TEN);
            expect(!rgb.contains("-colorspace"), f + ": RGB must not name a YUV colourspace");
            expect(!String.join(" ", rgb).contains("colormatrix"), f + ": RGB must not name a colour matrix");
            expect(f.settings(Chroma.YUV420, Depth.TEN).contains("-colorspace"),
                    f + ": 4:2:0 must still signal its colourspace");
        }

        // H.264 errors outright when the profile cannot hold the pixel format, so each combination
        // has to name its own rather than inherit a fixed "high".
        expect(ExportFormat.H264.settings(Chroma.YUV420, Depth.EIGHT).contains("high"), "8-bit 4:2:0 is High");
        expect(ExportFormat.H264.settings(Chroma.YUV420, Depth.TEN).contains("high10"), "10-bit 4:2:0 is High 10");
        expect(ExportFormat.H264.settings(Chroma.YUV444, Depth.EIGHT).contains("high444"), "4:4:4 is High 4:4:4");

        // psy-rd forces a chroma QP penalty on 4:4:4, so it is switched off there and only there.
        expect(String.join(" ", ExportFormat.H265.settings(Chroma.YUV444, Depth.TEN)).contains("psy-rd=0"),
                "x265 4:4:4 must disable psy-rd");
        expect(!String.join(" ", ExportFormat.H265.settings(Chroma.YUV420, Depth.TEN)).contains("psy-rd=0"),
                "x265 4:2:0 must keep psy-rd");
        expect(String.join(" ", ExportFormat.H265.settings(Chroma.YUV444, Depth.TEN)).contains("colorprim=bt709"),
                "the 4:4:4 fix must not displace the colour signalling");

        if (failures != 0)
            throw new AssertionError(failures + " export-format failure(s)");
        System.out.println("ExportFormatCheck: PASS");
    }

    private static int count(List<String> list, String s) {
        return (int) list.stream().filter(s::equals).count();
    }

    private static int depthOf(String pixFmt) {
        if (pixFmt.contains("16") || pixFmt.contains("48"))
            return 16;
        if (pixFmt.contains("12"))
            return 12;
        if (pixFmt.contains("10"))
            return 10;
        return 8;
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
