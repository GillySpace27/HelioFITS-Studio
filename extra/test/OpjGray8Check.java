package org.helioviewer.jhv.view.j2k.opj;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Converting a decoded plane to 8-bit grey in place gives exactly the bytes the old route did: copy
 * the plane to an {@code int[]}, shift off the bits past eight, clamp, put one byte at a time.
 * Fixtures are 8-, 12- and 16-bit codestreams (extra/test/data/opj-*bit.j2k), whole and at a
 * reduced resolution and a sub-area, so the shift and the clamp are each exercised.
 */
public final class OpjGray8Check {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** The conversion as OpjDecoder did it before. */
    private static byte[] old(OpenJpeg.Decoded image) {
        ByteBuffer out = ByteBuffer.allocate(image.samples().length);
        int[] samples = image.samples();
        int shift = Math.max(0, image.precision() - 8);
        for (int i = 0; i < samples.length; i++)
            out.put(i, (byte) Math.clamp(samples[i] >> shift, 0, 255));
        return out.array();
    }

    public static void main(String[] args) throws Exception {
        for (int bits : new int[]{8, 12, 16}) {
            byte[] stream = Files.readAllBytes(Path.of("extra/test/data/opj-" + bits + "bit.j2k"));
            int[][] cases = {{0, 0, 0, 0, 0}, {1, 0, 0, 0, 0}, {0, 16, 8, 80, 64}}; // reduce, then area x0 y0 x1 y1
            for (int[] c : cases) {
                OpenJpeg.Decoded reference = OpenJpeg.decode(stream, false, c[0], 1, c[1], c[2], c[3], c[4]);
                byte[] expected = old(reference);
                byte[] direct = OpenJpeg.decode(stream, false, c[0], 1, c[1], c[2], c[3], c[4], plane -> {
                    ByteBuffer out = ByteBuffer.allocateDirect(plane.width() * plane.height());
                    plane.gray8(out);
                    byte[] bytes = new byte[out.capacity()];
                    out.get(0, bytes);
                    return bytes;
                });
                String what = bits + "-bit, reduce " + c[0] + (c[3] > 0 ? ", area" : "");
                expect(what + ": " + expected.length + " pixels, in place equals the old route", Arrays.equals(expected, direct));
                if (c[0] == 0 && c[3] == 0) {
                    expect(what + ": precision reads as " + reference.precision(), reference.precision() == bits);
                    boolean low = false, high = false;
                    for (byte b : expected) {
                        low |= (b & 0xFF) == 0;
                        high |= (b & 0xFF) == 255;
                    }
                    expect(what + ": the full 0..255 range is present", low && high);
                }
            }
        }
        System.out.println(failures == 0 ? "OpjGray8Check: PASS" : "OpjGray8Check: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private OpjGray8Check() {}

}
