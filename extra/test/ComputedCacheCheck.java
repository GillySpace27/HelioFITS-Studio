package org.helioviewer.jhv.image.fourier;

import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.io.APIRequest;
import org.helioviewer.jhv.io.DataUri;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.view.View;

/**
 * The computed-frame cache can fail in ways a movie would not show: frames that come back with
 * the right size but the wrong bytes (an endianness or offset slip), a region or a scale that is
 * not the one the job attached, or a key that ignores the parameters and serves another band's
 * frames. A stub view with identifiable frames goes through store and load, and the mapped frames
 * are compared pixel for pixel; then the same frames under a different band must miss, and a
 * source without frame identities must not be cached at all.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.image.fourier.ComputedCacheCheck
 */
public final class ComputedCacheCheck {

    private static int failures;

    private static final class Stub implements View {
        final int n = 3, w = 40, h = 24;
        final long t0 = 1_700_000_000_000L;
        final DecodedImage[] frames = new DecodedImage[n];
        final boolean identified;

        Stub(boolean identified) {
            this.identified = identified;
            for (int k = 0; k < n; k++) {
                short[] half = new short[w * h];
                for (int i = 0; i < half.length; i++)
                    half[i] = Float.floatToFloat16((float) ((i + 7 * k) % 251) / 251f);
                ImageBuffer buffer = ImageBuffer.fromShorts(w, h, ImageBuffer.Format.Gray16F, half, ImageFilter.NONE); // no metadata: the cache never reads it
                buffer.setPhysicalScale(new ImageBuffer.PhysicalScale(-2 - k, 2 + k, y -> y, "Y = t", y -> y));
                frames[k] = new DecodedImage(buffer, new Region(-1.5 + k, -0.75, 3, 1.5));
            }
        }

        @Nullable
        @Override
        public String frameKey(int frame) {
            return identified ? "stub://frame/" + frame : null;
        }

        @Nullable
        @Override
        public DecodedImage frameImage(int frame) {
            return frames[frame];
        }

        @Override
        public int getMaximumFrameNumber() {
            return n - 1;
        }

        @Override
        public JHVTime getFrameTime(int frame) {
            return new JHVTime(t0 + 60_000L * frame);
        }

        // The rest is unused by the cache.
        @Override public void decode(Position viewpoint, double pixFactor, float factor) {}
        @Override public void setDataHandler(DataHandler dataHandler) {}
        @Override public void abolish() {}
        @Override public void clearCache() {}
        @Override public boolean isDownloading() { return false; }
        @Nullable @Override public APIRequest getAPIRequest() { return null; }
        @Override public void setRange(double min, double max) {}
        @Override public void setFilter(ImageFilter.Type t) {}
        @Override public ImageFilter.Type getFilter() { return ImageFilter.Type.None; }
        @Nullable @Override public String getBaseName() { return "stub"; }
        @Nullable @Override public DataUri.Format getFormat() { return null; }
        @Nullable @Override public int[] getNativeSize() { return new int[]{w, h}; }
        @Nullable @Override public LUT getDefaultLUT() { return null; }
        @Override public boolean isMultiFrame() { return true; }
        @Override public int getCurrentFrameNumber() { return 0; }
        @Override public boolean isComplete() { return true; }
        @Nullable @Override public AtomicBoolean getFrameCompletion(int frame) { return null; }
        @Override public JHVTime getFirstTime() { return getFrameTime(0); }
        @Override public JHVTime getLastTime() { return getFrameTime(n - 1); }
        @Override public boolean setNearestFrame(JHVTime time) { return true; }
        @Override public JHVTime getNearestTime(JHVTime time) { return time; }
        @Override public JHVTime getLowerTime(JHVTime time) { return time; }
        @Override public JHVTime getHigherTime(JHVTime time) { return time; }
        @Nullable @Override public MetaData getMetaData(JHVTime time) { return null; }
        @Nonnull @Override public String getXMLMetaData() { return ""; }
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean samePixels(DecodedImage a, DecodedImage b) {
        ShortBuffer x = (ShortBuffer) a.imageBuffer().buffer, y = (ShortBuffer) b.imageBuffer().buffer;
        int n = a.imageBuffer().width * a.imageBuffer().height;
        if (b.imageBuffer().width != a.imageBuffer().width || b.imageBuffer().height != a.imageBuffer().height)
            return false;
        for (int i = 0; i < n; i++)
            if (x.get(i) != y.get(i))
                return false;
        return true;
    }

    public static void main(String[] args) {
        Stub stub = new Stub(true);
        FourierParams band = new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.PASS, 200, 800, FourierParams.Direction.POSITIVE, 1, 512, 256);
        FourierParams other = new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.PASS, 400, 6800, FourierParams.Direction.POSITIVE, 1, 512, 256);
        ComputedCache.forget(stub, band);
        ComputedCache.forget(stub, other);
        try {
            expect("nothing cached before the first store", ComputedCache.load(stub, band) == null);

            FourierFilter.Spectrum spectrum = new FourierFilter.Spectrum(FourierParams.Kind.RADIAL, new double[]{1, 2}, new double[]{3, 4}, new double[]{5, 6});
            ComputedCache.Hit stored = ComputedCache.store(stub, band, stub.frames, spectrum);
            expect("store hands back mapped frames, not the originals", stored.frames()[0] != stub.frames[0]);
            for (int k = 0; k < stub.n; k++)
                expect("stored frame " + k + " has the same pixels", samePixels(stub.frames[k], stored.frames()[k]));

            ComputedCache.Hit hit = ComputedCache.load(stub, band);
            expect("the same filter over the same frames hits", hit != null);
            if (hit != null) {
                for (int k = 0; k < stub.n; k++) {
                    expect("loaded frame " + k + " has the same pixels", samePixels(stub.frames[k], hit.frames()[k]));
                    Region r = hit.frames()[k].region(), o = stub.frames[k].region();
                    expect("loaded frame " + k + " keeps its region", r.llx == o.llx && r.lly == o.lly && r.width == o.width && r.height == o.height);
                    ImageBuffer.PhysicalScale s = hit.frames()[k].imageBuffer().physicalScale();
                    expect("loaded frame " + k + " keeps its symmetric scale", s != null && s.min() == -2 - k && s.max() == 2 + k && s.toPhysical(0.5) == 0);
                    expect("loaded frame " + k + " reads back through sampleAt", hit.frames()[k].imageBuffer().sampleAt(5, 3) == stub.frames[k].imageBuffer().sampleAt(5, 3));
                }
                expect("the spectrum rides along", hit.spectrum() != null && hit.spectrum().rate()[1] == 2 && hit.spectrum().powerNegative()[0] == 5);
            }

            expect("a different band misses", ComputedCache.load(stub, other) == null);
            Stub anonymous = new Stub(false);
            expect("a source without frame identities is not keyed", ComputedCache.key(anonymous, band) == null);
            expect("and storing it is a no-op that returns the frames as they were", ComputedCache.store(anonymous, band, anonymous.frames, null).frames() == anonymous.frames);
        } finally {
            ComputedCache.forget(stub, band);
        }
        expect("the entry is gone after forget", ComputedCache.load(stub, band) == null);

        if (failures > 0)
            throw new AssertionError(failures + " computed cache failure(s)");
        System.out.println("ComputedCacheCheck: PASS");
    }

}
