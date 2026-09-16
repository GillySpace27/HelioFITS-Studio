package org.helioviewer.jhv.view.j2k;

import java.util.Arrays;

/**
 * The rectangle the viewer asks for reaches the decoder in the coordinates the decoder uses.
 *
 * <p>The viewer asks in the coordinates of the resolution level it wants. JPEG 2000 addresses a
 * region in the image's own full-size coordinates whatever the level. At the top level the two
 * agree, which is exactly why a mistake here survives the obvious test and then decodes the wrong
 * part of the picture at every other level.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.view.j2k.OpjDecoderAreaCheck
 */
public final class OpjDecoderAreaCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        J2KParams.SubImage sub = new J2KParams.SubImage(64, 32, 128, 96);

        int[] top = OpjDecoder.area(sub, 0);
        expect("at the top level the rectangle is itself, " + Arrays.toString(top),
                Arrays.equals(new int[]{64, 32, 192, 128}, top));

        int[] half = OpjDecoder.area(sub, 1);
        expect("one level down it covers twice as much of the image, " + Arrays.toString(half),
                Arrays.equals(new int[]{128, 64, 384, 256}, half));

        int[] eighth = OpjDecoder.area(sub, 3);
        expect("three levels down, eight times, " + Arrays.toString(eighth),
                Arrays.equals(new int[]{512, 256, 1536, 1024}, eighth));

        // The decoded result is the rectangle divided by the level again, so the request is
        // self-consistent: same picture, fewer pixels.
        expect("and the result at that level is the size the viewer asked for",
                (eighth[2] - eighth[0]) >> 3 == sub.w() && (eighth[3] - eighth[1]) >> 3 == sub.h());

        System.out.println(failures == 0 ? "OpjDecoderAreaCheck: PASS" : "OpjDecoderAreaCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private OpjDecoderAreaCheck() {}

}
