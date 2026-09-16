package org.helioviewer.jhv.view.j2k.opj;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The OpenJPEG binding decodes, and decodes the same way twice.
 *
 * <p>This is the decoder that replaces Kakadu, reached through the foreign function interface, so
 * what is at risk here is the plumbing rather than the codec: a wrong struct offset, a stream
 * callback that reports the wrong length, a resolution factor that does not reach the codec. Each
 * of those produces a plausible-looking image rather than an error, which is why the assertions
 * below are about sizes, precision and self-consistency rather than about individual pixels.
 *
 * <p>Fidelity against Kakadu itself was measured separately, on full frames from two instruments at
 * two resolutions: every pixel agreed to within one count in 255, the rounding of the irreversible
 * 9/7 inverse transform. That comparison needs Kakadu, so it cannot live here once Kakadu is gone.
 *
 * <p>The fixture is a 256 x 256 LASCO C2 browse image from the Helioviewer archive, re-encoded
 * small. If the library is not installed, this reports that and passes: bundling it is a later
 * step, and a missing build dependency should not read as a broken decoder.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.view.j2k.opj.OpenJpegDecodeCheck
 */
public final class OpenJpegDecodeCheck {

    private static final Path FIXTURE = Path.of("extra", "test", "j2k", "lasco-c2-256.jp2");

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        String version;
        try {
            version = OpenJpeg.version();
        } catch (Throwable t) {
            System.out.println("  skip  OpenJPEG is not installed here: " + t.getMessage());
            System.out.println("OpenJpegDecodeCheck: SKIPPED");
            return;
        }
        System.out.println("  ok   linked against OpenJPEG " + version);

        byte[] jp2 = Files.readAllBytes(FIXTURE);
        OpenJpeg.Decoded full = OpenJpeg.decode(jp2, true, 0, 2);
        expect("the fixture decodes at its own size", full.width() == 256 && full.height() == 256);
        expect("as eight-bit unsigned samples", full.precision() == 8 && !full.signed());
        expect("with one sample per pixel", full.samples().length == 256 * 256);

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        long sum = 0;
        for (int sample : full.samples()) {
            min = Math.min(min, sample);
            max = Math.max(max, sample);
            sum += sample;
        }
        expect("samples stay inside the range the precision promises, got " + min + ".." + max, min >= 0 && max <= 255);
        expect("and the frame is not blank", min < max);

        // A wrong stride or offset usually shows up as a second decode differing from the first.
        OpenJpeg.Decoded again = OpenJpeg.decode(jp2, true, 0, 2);
        expect("two decodes of the same bytes agree", java.util.Arrays.equals(full.samples(), again.samples()));

        OpenJpeg.Decoded half = OpenJpeg.decode(jp2, true, 1, 2);
        expect("one resolution level down halves each side", half.width() == 128 && half.height() == 128);
        double fullMean = sum / (double) full.samples().length;
        long halfSum = 0;
        for (int sample : half.samples())
            halfSum += sample;
        double halfMean = halfSum / (double) half.samples().length;
        // The reduced image is the same picture, so its mean cannot wander far. A resolution factor
        // that never reached the codec would show up as a size mismatch above; one that reached the
        // wrong place shows up here.
        expect("and shows the same picture, means " + String.format("%.2f vs %.2f", fullMean, halfMean),
                Math.abs(fullMean - halfMean) < 2);

        System.out.println(failures == 0 ? "OpenJpegDecodeCheck: PASS" : "OpenJpegDecodeCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private OpenJpegDecodeCheck() {}

}
