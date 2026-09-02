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

/**
 * The one way the decorator can go wrong that no movie would show: abolishing the view it
 * wraps. That view is the layer's only copy of the source (for a JPEG 2000 stream, the Kakadu
 * source itself), so "turn the filter off" would become "reload the layer". A stub view counts
 * abolish calls while the real radial job runs over its synthetic frames, and the decorator has
 * to serve those frames, fall back to the source when disposed, and forward abolish exactly once.
 *
 * <p>Run: java -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.view.ComputedViewCheck
 */
public final class ComputedViewCheck {

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

    public static void main(String[] args) throws Exception {
        // BasicMetaData reaches Sun, which reaches SPICE: the same bootstrap as EclipticCheck
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        Stub stub = new Stub();
        // 64 px over one solar radius: 10 870 km per pixel, one pixel per minute is 181 km/s
        FourierParams params = new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.PASS, 100, 300, FourierParams.Direction.POSITIVE, 1, 64, 64);

        // the real job on the stub's frames, synchronously
        DecodedImage[] out = params.job().run(stub, s -> {}, p -> {});
        expect("job returns one frame per source frame", out.length == stub.n);
        ImageBuffer.PhysicalScale scale = out[8].imageBuffer().physicalScale();
        expect("PASS output carries a symmetric physical scale", scale != null && scale.min() == -scale.max() && scale.max() > 0);
        double mean = meanValue(out[8].imageBuffer()), spread = spread(out[8].imageBuffer());
        expect(String.format("PASS output sits around mid-grey with structure (mean %.3f, spread %.3f)", mean, spread), Math.abs(mean - 0.5) < 0.1 && spread > 0.01);

        // the decorator, installed the way ImageLayer.swapView does it
        ComputedView view = new ComputedView(stub, params, s -> {});
        AtomicReference<View.ImageData> received = new AtomicReference<>();
        view.setDataHandler(received::set);
        expect("not ready before install; frameImage falls back to the source", !view.isReady() && view.frameImage(3) == stub.frames[3]);
        view.install(out);
        expect("ready after install; frameImage serves the computed frame", view.isReady() && view.frameImage(3) == out[3]);
        expect("times and counts are the wrapped view's", view.getMaximumFrameNumber() == 15 && view.getFrameTime(5).equals(stub.getFrameTime(5)) && view.getFilter() == ImageFilter.Type.None);

        stub.setNearestFrame(stub.getFrameTime(7));
        view.decode(new Position(stub.getFrameTime(7), 1, 0, 0), 1, 1);
        EventQueue.invokeAndWait(() -> {});
        expect("decode publishes the computed frame 7 without touching the source", received.get() != null && received.get().imageBuffer() == out[7].imageBuffer() && stub.decodeCalls == 0);

        view.dispose();
        expect("dispose drops the computed frames and never abolishes the wrapped view",
                !view.isReady() && ImageBufferCache.get(new ComputedView.ComputedKey(view, 3)) == null && stub.abolishCalls == 0 && view.frameImage(3) == stub.frames[3]);
        received.set(null);
        view.decode(new Position(stub.getFrameTime(7), 1, 0, 0), 1, 1);
        expect("after dispose decode passes through to the source", stub.decodeCalls == 1);

        view.abolish();
        expect("abolish forwards to the wrapped view exactly once", stub.abolishCalls == 1);

        System.out.println(failures == 0 ? "ComputedViewCheck: PASS" : "ComputedViewCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static double meanValue(ImageBuffer b) {
        java.nio.ShortBuffer sb = (java.nio.ShortBuffer) b.buffer;
        double s = 0;
        int c = 0;
        for (int i = 0; i < b.width * b.height; i++) {
            float v = Float.float16ToFloat(sb.get(i));
            if (v > 0) {
                s += v;
                c++;
            }
        }
        return c == 0 ? Double.NaN : s / c;
    }

    private static double spread(ImageBuffer b) {
        java.nio.ShortBuffer sb = (java.nio.ShortBuffer) b.buffer;
        float min = 1, max = 0;
        for (int i = 0; i < b.width * b.height; i++) {
            float v = Float.float16ToFloat(sb.get(i));
            if (v > 0) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        return max - min;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private ComputedViewCheck() {}

}
