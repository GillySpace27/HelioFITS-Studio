package org.helioviewer.jhv.image;

import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.thread.ParallelRange;

//import com.google.common.base.Stopwatch;

public class ImageFilter {

    public static final ImageFilter NONE = new ImageFilter(null);
    private static final ImageFilter MGN = new ImageFilter(new FilterMGN());
    private static final ImageFilter WOW = new ImageFilter(new FilterWOW());

    public enum Type {
        None("No filter"),
        MGN("Multi-scale Gaussian normalization"),
        WOW("Wavelet-optimized whitening"),
        RHEF("Radial histogram equalizing filter");

        public final String description;

        Type(String _description) {
            description = _description;
        }
    }

    interface Algorithm {
        float[] filter(float[] data, int width, int height);

        /** For half-float input. The default widens to floats and filters those; RHEF works on the halves themselves. */
        default float[] filterHalf(short[] halves, int width, int height) {
            float[] data = new float[width * height];
            ParallelRange.run(height, (from, to) -> {
                for (int y = from; y < to; y++) {
                    int rowBase = y * width;
                    int rowEnd = rowBase + width;
                    for (int idx = rowBase; idx < rowEnd; idx++) {
                        data[idx] = Float.float16ToFloat(halves[idx]);
                    }
                }
            });
            return filter(data, width, height);
        }
    }

    private final Algorithm algorithm;

    private ImageFilter(Algorithm _algorithm) {
        algorithm = _algorithm;
    }

    private static ImageFilter of(Type type) {
        return switch (type) {
            case None -> NONE;
            case MGN -> MGN;
            case WOW -> WOW;
            case RHEF -> throw new IllegalArgumentException("RHEF requires image geometry");
        };
    }

    public static ImageFilter of(Type type, Region imageRegion, MetaData metaData) {
        return type == Type.RHEF
                ? new ImageFilter(new FilterRHEF(SunCenteredRegion.fromImageRegion(imageRegion, metaData.getSunShift())))
                : of(type);
    }

    /**
     * The layer's filter followed by the chain's stages. With no stages, or no first filter to put them
     * on, this is exactly of(type, region, meta): the single-filter path is not touched.
     */
    public static ImageFilter of(Type type, FilterChain chain, Region imageRegion, MetaData metaData) {
        if (chain == null || chain.isEmpty() || type == Type.None)
            return of(type, imageRegion, metaData);
        return of(type, chain, SunCenteredRegion.fromImageRegion(imageRegion, metaData.getSunShift()));
    }

    // The geometry already worked out; what the checks call.
    static ImageFilter of(Type type, FilterChain chain, SunCenteredRegion centered) {
        ImageFilter first = type == Type.RHEF ? new ImageFilter(new FilterRHEF(centered)) : of(type);
        if (chain == null || chain.isEmpty() || first.isNone())
            return first;
        return new ImageFilter(new ChainedAlgorithm(first.algorithm, chain, centered));
    }

    /** Where a later RHEF stage takes its input: rounded to half floats (the histogram path, as a single RHEF sees) or as float32 (the sort path). */
    public static final String RHEF_INPUT_PROPERTY = "jhv.filterChain.rhefInput";

    private static boolean rhefInputHalf() {
        return !"float".equalsIgnoreCase(System.getProperty(RHEF_INPUT_PROPERTY, "half"));
    }

    private static Algorithm algorithmOf(Type type, SunCenteredRegion region) {
        return switch (type) {
            case MGN -> MGN.algorithm;
            case WOW -> WOW.algorithm;
            case RHEF -> new FilterRHEF(region);
            case None -> throw new IllegalArgumentException("a chain stage needs a filter");
        };
    }

    /** Stage 1, then each stage of the chain on the one before: y = (1 - w) * y + w * F(y). */
    private static final class ChainedAlgorithm implements Algorithm {
        private final Algorithm first;
        private final FilterChain chain;
        private final SunCenteredRegion region;

        ChainedAlgorithm(Algorithm _first, FilterChain _chain, SunCenteredRegion _region) {
            first = _first;
            chain = _chain;
            region = _region;
        }

        @Override
        public float[] filter(float[] data, int width, int height) {
            return stages(first.filter(data, width, height), width, height);
        }

        @Override
        public float[] filterHalf(short[] halves, int width, int height) {
            return stages(first.filterHalf(halves, width, height), width, height);
        }

        private float[] stages(float[] y, int width, int height) {
            for (FilterChain.Stage stage : chain.stages()) {
                float w = stage.strength();
                if (w == 0) // passes y through
                    continue;
                Algorithm alg = algorithmOf(stage.type(), region);
                // RHEF ranks in place, so a blend that still needs y keeps a copy first.
                float[] keep = w < 1 ? y.clone() : null;
                float[] f = alg instanceof FilterRHEF && rhefInputHalf()
                        ? alg.filterHalf(toHalves(y, width, height), width, height)
                        : alg.filter(y, width, height);
                y = w == 1 ? f : mix(keep, f, w, width, height);
            }
            return y;
        }
    }

    private static short[] toHalves(float[] data, int width, int height) {
        short[] halves = new short[data.length];
        ParallelRange.run(height, (from, to) -> {
            for (int i = from * width, end = to * width; i < end; i++)
                halves[i] = Float.floatToFloat16(data[i]);
        });
        return halves;
    }

    // (1 - w) * y + w * f, into f's array
    private static float[] mix(float[] y, float[] f, float w, int width, int height) {
        float a = 1 - w;
        ParallelRange.run(height, (from, to) -> {
            for (int i = from * width, end = to * width; i < end; i++)
                f[i] = a * y[i] + w * f[i];
        });
        return f;
    }

    /** Package-visible for checks: the blend on its own. */
    static float[] blendForCheck(float[] y, float[] f, float w, int width, int height) {
        return mix(y.clone(), f.clone(), w, width, height);
    }

    boolean isNone() {
        return algorithm == null;
    }

    private static final float BDIV = 1 / 255f;

    float[] apply(byte[] array, int width, int height) {
        int length = width * height;

        float[] data = new float[length];
        ParallelRange.run(height, (from, to) -> {
            for (int y = from; y < to; y++) {
                int rowBase = y * width;
                int rowEnd = rowBase + width;
                for (int idx = rowBase; idx < rowEnd; idx++) {
                    data[idx] = ((array[idx] + 256) & 0xFF) * BDIV;
                }
            }
        });
        return algorithm.filter(data, width, height);
    }

    float[] apply(short[] array, int width, int height) {
        return algorithm.filterHalf(array, width, height);
    }

}
