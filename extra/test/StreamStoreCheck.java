package org.helioviewer.jhv.view.uri;

import java.io.File;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.io.LascoBackground;
import org.helioviewer.jhv.metadata.Region;

/**
 * The streaming store's three jobs, each as something that can fail: the window of mapped frames
 * stays inside its byte budget and drops the least recently used; the disk budget removes the
 * oldest frames and never the newest; and a frame written and mapped back is the same pixels with
 * the same scale, while a torn file is refused rather than mapped.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.view.uri.StreamStoreCheck
 */
public final class StreamStoreCheck {

    private static int failures;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final ImageProcessingSettings.FITSParameters STATE = new ImageProcessingSettings.FITSParameters(
            ImageProcessingSettings.ClippingMode.Percentile05, 0, 1, ImageProcessingSettings.ScalingMode.Gamma, 1 / 2.2, 1, 1, 0, true,
            LascoBackground.Choice.DEFAULT);

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("streamstore-home").toString()); // Settings is read; never the real one

        window();
        victims();
        roundTrip();
        System.out.println(failures == 0 ? "StreamStoreCheck: all ok" : "StreamStoreCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void window() {
        var w = new StreamStore.Window<String, String>(() -> 100);
        w.put("a", "A", 40);
        w.put("b", "B", 40);
        w.get("a"); // a is now the most recently used
        w.put("c", "C", 40); // 120 > 100: b, the least recently used, goes
        check("window drops the least recently used", w.get("b") == null && w.get("a") != null && w.get("c") != null);
        check("window holds within its budget", w.held() <= 100 && w.size() == 2);
        w.put("big", "BIG", 500);
        check("window always keeps the newest, however large", w.get("big") != null && w.size() == 1);
        w.removeIf("big"::equals);
        check("window removeIf empties it and its byte count", w.size() == 0 && w.held() == 0);
    }

    private static void victims() {
        List<StreamStore.Entry> e = List.of(new StreamStore.Entry("new", 300, 40), new StreamStore.Entry("old", 100, 40), new StreamStore.Entry("mid", 200, 40));
        check("disk budget removes the oldest first", StreamStore.victims(e, 90).equals(List.of("old")));
        check("disk budget removes as many as it takes", StreamStore.victims(e, 50).equals(List.of("old", "mid")));
        check("disk budget never removes the newest", StreamStore.victims(e, 0).equals(List.of("old", "mid")));
        check("disk budget leaves a folder that fits", StreamStore.victims(e, 1000).isEmpty());
    }

    private static DecodedImage frame(int w, int h) {
        short[] half = new short[w * h];
        for (int i = 0; i < half.length; i++)
            half[i] = Float.floatToFloat16((i % 251) / 251f);
        ImageBuffer b = ImageBuffer.fromShorts(w, h, ImageBuffer.Format.Gray16F, half, ImageFilter.NONE);
        b.setPhysicalScale(new ImageBuffer.PhysicalScale(-3f, 7f, y -> y, "Y = t", y -> y));
        return new DecodedImage(b, new Region(-1, -1, 2, 2));
    }

    private static void roundTrip() throws Exception {
        Path dir = Files.createTempDirectory("streamstore");
        DecodedImage src = frame(48, 32);
        check("a plain half-float frame is storable", StreamStore.storable(src.imageBuffer()));
        short[] want = new short[48 * 32];
        ((ShortBuffer) src.imageBuffer().buffer).duplicate().get(want);

        Region region = new Region(-1, -1, 2, 2);
        DecodedImage back = StreamStore.store(dir, "f.f16", src, region, STATE);
        check("store hands back a different (mapped) buffer", back != src);
        short[] got = new short[want.length];
        ((ShortBuffer) back.imageBuffer().buffer).duplicate().get(got);
        check("mapped pixels equal the written pixels", java.util.Arrays.equals(want, got));
        check("size survives", back.imageBuffer().width == 48 && back.imageBuffer().height == 32);
        var scale = back.imageBuffer().physicalScale();
        check("physical range survives", scale != null && scale.min() == -3f && scale.max() == 7f);
        check("region is the caller's", back.region().equals(region));

        DecodedImage again = StreamStore.read(dir.resolve("f.f16"), region, STATE);
        check("a second read maps the same frame", again != null && again != src);

        Path torn = dir.resolve("torn.f16");
        byte[] bytes = Files.readAllBytes(dir.resolve("f.f16"));
        Files.write(torn, java.util.Arrays.copyOf(bytes, bytes.length - 2));
        check("a torn file is refused", StreamStore.read(torn, region, STATE) == null);
        check("a missing file is a miss", StreamStore.read(dir.resolve("none.f16"), region, STATE) == null);

        File f = Files.createTempFile("id", ".fits").toFile();
        check("the name follows the parameters", !StreamStore.id(f, "a").equals(StreamStore.id(f, "b")) && StreamStore.id(f, "a").equals(StreamStore.id(f, "a")));
        StreamStore.mark(List.of(f));
        check("a marked file streams, another does not", StreamStore.isStreaming(f) && !StreamStore.isStreaming(new File(f.getPath() + "x")));

        StreamStore.trim(dir, 0);
        check("trim keeps the newest frame of an over-budget folder", Files.list(dir).filter(p -> p.toString().endsWith(".f16")).count() == 1);
    }
}
