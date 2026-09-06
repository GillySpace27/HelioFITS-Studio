package org.helioviewer.jhv.view;

import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageBufferCache;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.fourier.FourierParams;
import org.helioviewer.jhv.metadata.BasicMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.image.fourier.FourierParams;
import org.helioviewer.jhv.image.fourier.FourierPreview;

/**
 * The live preview has one job that matters: to answer the same question the full run answers. A
 * preview that ignored the band, or that filtered on a coarser time grid, would still produce a
 * plausible picture and would quietly mislead whoever was dragging the band, which is worse than
 * no preview at all.
 *
 * <p>So it has to react to the band and to the direction, keep the full run's time grid (dt and
 * the number of time samples are what set the rate axis; coarsening them would move the band under
 * the user), survive being asked twice for the same thing, and land near what the full run
 * produces for the same frame.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.view.FourierPreviewCheck
 */
public final class FourierPreviewCheck {

    private static int failures;

    /** Sixteen 64 x 64 frames: a blob moving outward one pixel per minute over a static ring. */
    private static final class Stub implements View {
        final int n = 16, size = 64;
        final long t0 = 1_700_000_000_000L;
        final MetaData meta = new BasicMetaData(size, size, "stub");
        final DecodedImage[] frames = new DecodedImage[n];
        int current;
        int abolishCalls, decodeCalls;
        ImageFilter.Type filter = ImageFilter.Type.None;
        DataHandler handler;

        Stub() {
            Region region = meta.roiToRegion(0, 0, size, size, 1, 1);
            for (int k = 0; k < n; k++) {
                short[] half = new short[size * size];
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++) {
                        double dx = x - 31.5, dy = y - 31.5, r = Math.hypot(dx, dy);
                        double v = 0.3 + 0.4 * Math.exp(-(r - (8 + k)) * (r - (8 + k)) / 8) + 0.2 * Math.exp(-(r - 24) * (r - 24) / 8);
                        half[y * size + x] = Float.floatToFloat16((float) Math.clamp(v, 0.01, 1));
                    }
                ImageBuffer buffer = ImageBuffer.fromShorts(size, size, ImageBuffer.Format.Gray16F, half, ImageFilter.of(ImageFilter.Type.None, region, meta));
                buffer.setPhysicalScale(new ImageBuffer.PhysicalScale(0, 1, y -> y, "Y = t", y -> y));
                frames[k] = new DecodedImage(buffer, region);
            }
        }

        @Nullable
        @Override
        public DecodedImage frameImage(int frame) {
            return frames[frame];
        }

        @Override
        public void abolish() {
            abolishCalls++;
        }

        @Override
        public void decode(Position viewpoint, double pixFactor, float factor) {
            decodeCalls++;
        }

        @Override
        public void setFilter(ImageFilter.Type t) {
            filter = t;
        }

        @Override
        public ImageFilter.Type getFilter() {
            return filter;
        }

        @Override
        public void setDataHandler(DataHandler dataHandler) {
            handler = dataHandler;
        }

        @Override
        public int getCurrentFrameNumber() {
            return current;
        }

        @Override
        public int getMaximumFrameNumber() {
            return n - 1;
        }

        @Override
        public boolean isMultiFrame() {
            return true;
        }

        @Override
        public JHVTime getFrameTime(int frame) {
            return new JHVTime(t0 + 60_000L * frame);
        }

        @Override
        public JHVTime getFirstTime() {
            return getFrameTime(0);
        }

        @Override
        public JHVTime getLastTime() {
            return getFrameTime(n - 1);
        }

        private int nearest(JHVTime time) {
            return Math.clamp((int) Math.round((time.milli - t0) / 60_000.), 0, n - 1);
        }

        @Override
        public boolean setNearestFrame(JHVTime time) {
            current = nearest(time);
            return true;
        }

        @Override
        public JHVTime getNearestTime(JHVTime time) {
            return getFrameTime(nearest(time));
        }

        @Override
        public JHVTime getLowerTime(JHVTime time) {
            return getFrameTime(Math.max(0, nearest(time) - 1));
        }

        @Override
        public JHVTime getHigherTime(JHVTime time) {
            return getFrameTime(Math.min(n - 1, nearest(time) + 1));
        }

        @Override
        public MetaData getMetaData(JHVTime time) {
            return meta;
        }
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** How far apart two frames are, as a fraction of the full stored range. */
    private static double rms(DecodedImage a, DecodedImage b) {
        java.nio.ShortBuffer x = (java.nio.ShortBuffer) a.imageBuffer().buffer;
        java.nio.ShortBuffer y = (java.nio.ShortBuffer) b.imageBuffer().buffer;
        int n = a.imageBuffer().width * a.imageBuffer().height;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double d = Float.float16ToFloat(x.get(i)) - Float.float16ToFloat(y.get(i));
            sum += d * d;
        }
        return Math.sqrt(sum / n);
    }

    public static void main(String[] args) throws Exception {
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        Stub stub = new Stub();
        // The same geometry ComputedViewCheck uses: 64 px over a solar radius, one pixel a minute
        // is 181 km/s, so a 100 to 300 km/s outward band is the moving ring and not the static one.
        FourierParams outward = new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.PASS,
                100, 300, FourierParams.Direction.POSITIVE, 1, 64, 64);

        FourierPreview preview = FourierPreview.prepare(stub, outward, s -> {});
        String grid = preview.grid();
        expect("the preview keeps the full run's time grid, coarsening only the polar one: " + grid,
                grid.endsWith(" x " + stub.n));

        DecodedImage out = preview.render(outward, 8);
        expect("an outward pass renders the frame it was asked for",
                out != null && out.imageBuffer().width == stub.size);

        FourierParams inward = new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.PASS,
                100, 300, FourierParams.Direction.NEGATIVE, 1, 64, 64);
        DecodedImage in = preview.render(inward, 8);
        expect("reversing the direction changes the picture, so the band is really applied",
                in != null && rms(out, in) > 0.02);

        FourierParams notch = new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.NOTCH,
                100, 300, FourierParams.Direction.POSITIVE, 1, 64, 64);
        DecodedImage no = preview.render(notch, 8);
        expect("and so is pass against notch", no != null && rms(out, no) > 0.02);

        DecodedImage again = preview.render(outward, 8);
        expect("asking twice for the same band gives the same frame: the cube is restored, not consumed",
                again != null && rms(out, again) == 0);

        DecodedImage other = preview.render(outward, 12);
        expect("a different frame of the same band is a different picture", other != null && rms(out, other) > 0.002);

        // Against the real thing: the same band through the full job, same frame.
        DecodedImage[] full = outward.job().run(stub, s -> {}, p -> {});
        double apart = rms(out, full[8]);
        expect(String.format("the preview lands near the full run on the same frame (rms %.4f of the range)", apart), apart < 0.15);

        long started = System.nanoTime();
        preview.render(outward, 8);
        System.out.printf("  ..     one preview frame on this stub: %.0f ms%n", (System.nanoTime() - started) / 1e6);

        if (failures > 0)
            throw new AssertionError(failures + " preview failure(s)");
        System.out.println("FourierPreviewCheck: PASS");
    }

}
