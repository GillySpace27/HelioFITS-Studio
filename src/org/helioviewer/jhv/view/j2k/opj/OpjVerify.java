package org.helioviewer.jhv.view.j2k.opj;

import java.nio.ByteBuffer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;

/**
 * Decoding each frame a second time, without Kakadu, and saying whether the two agree.
 *
 * <p>Fixtures prove the rebuild on the frames somebody thought to capture. This proves it on
 * whatever the archives actually serve, in the order a real session asks for it, which is the part
 * that finds the case nobody predicted. It runs only under -Djhv.opj.verify and changes nothing
 * the application draws: the picture on screen is still Kakadu's until the swap.
 *
 * <p>The comparison is of means rather than of pixels, because the two decoders are handed
 * different jobs here: Kakadu renders a region at an arbitrary scale through its compositor, while
 * this decodes the frame at a power-of-two reduction. Means over the same frame at the same
 * reduction agree closely when the rebuild is right, and diverge unmistakably when the packets are
 * out of order, which is the failure worth catching.
 */
public final class OpjVerify {

    private static int frames, agreed, rebuiltNothing, refused;

    /**
     * @param bins   the JPIP bins as they stand for this frame
     * @param kakadu the greyscale bytes Kakadu produced, one per pixel, or null when it made colour
     */
    public static synchronized void compare(@Nullable DataBinCache bins, int frame, int level,
                                            int width, int height, @Nullable ByteBuffer kakadu) {
        if (bins == null)
            return;
        frames++;
        try {
            byte[] stream = Codestream.build(bins, frame);
            if (stream == null) {
                rebuiltNothing++;
                Log.info("opj verify: frame " + frame + " level " + level + ": nothing to rebuild yet");
                return;
            }

            OpenJpeg.Decoded image = OpenJpeg.decode(stream, false, level, 2);
            String shape = image.width() + "x" + image.height() + " against Kakadu's " + width + "x" + height;
            if (kakadu == null || image.width() != width || image.height() != height) {
                Log.info("opj verify: frame " + frame + " level " + level + ": rebuilt " + stream.length
                        + " bytes, decoded " + shape + " (not compared)");
                return;
            }

            double theirs = mean(kakadu, width * height);
            double ours = meanOf(image.samples());
            boolean close = Math.abs(theirs - ours) < 1; // one count in 255, the rounding the two differ by
            if (close)
                agreed++;
            Log.info("opj verify: frame " + frame + " level " + level + ": rebuilt " + stream.length
                    + " bytes, " + shape + ", mean " + String.format("%.2f", ours) + " against "
                    + String.format("%.2f", theirs) + (close ? " (agree)" : " (DIFFER)"));
        } catch (UnsupportedOperationException e) {
            refused++;
            Log.info("opj verify: frame " + frame + ": the rebuild does not cover this image: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.warn("opj verify: frame " + frame + " level " + level + " failed", e);
        }
    }

    /** What the session saw, for the log at exit. */
    public static synchronized String summary() {
        return frames + " frames verified, " + agreed + " agreed, " + rebuiltNothing
                + " had nothing to rebuild, " + refused + " outside what the rebuild covers";
    }

    private static double mean(ByteBuffer buffer, int count) {
        long sum = 0;
        for (int i = 0; i < count; i++)
            sum += buffer.get(i) & 0xFF;
        return sum / (double) count;
    }

    private static double meanOf(int[] samples) {
        long sum = 0;
        for (int sample : samples)
            sum += sample;
        return sum / (double) samples.length;
    }

    private OpjVerify() {}

}
