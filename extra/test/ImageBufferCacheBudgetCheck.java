package org.helioviewer.jhv.image;

import java.util.Set;

import org.helioviewer.jhv.metadata.Region;

/** Shrinking the decoded-frame budget evicts frames, and an evicted frame nobody shows is freed by the next reap. */
public final class ImageBufferCacheBudgetCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Region region = new Region(0, 0, 4, 4);
        int side = 1024; // 1 MiB of Gray8 each
        Object[] keys = {"a", "b", "c", "d"};

        for (Object key : keys) {
            ImageBuffer buffer = ImageBuffer.createWriteBuffer(side, side, ImageBuffer.Format.Gray8, ImageFilter.NONE).finish();
            ImageBufferCache.put(key, new DecodedImage(buffer, region));
        }
        ImageBufferCache.setMaximum(2L << 20); // room for two
        waitFor(() -> countHeld(keys) <= 2);
        expect("a smaller budget evicts down to what fits, held " + countHeld(keys), countHeld(keys) <= 2);

        waitFor(() -> ImageBufferCache.retiredCount() >= 2);
        expect("the evicted frames wait to be freed, " + ImageBufferCache.retiredCount(), ImageBufferCache.retiredCount() >= 2);
        // Nothing displays them (an empty retained set), so the reap frees every one of them.
        ImageBufferCache.reap(Set.of());
        expect("and the reap frees them, " + ImageBufferCache.retiredCount() + " left", ImageBufferCache.retiredCount() == 0);

        System.out.println(failures == 0 ? "ImageBufferCacheBudgetCheck: PASS" : "ImageBufferCacheBudgetCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int countHeld(Object[] keys) {
        int held = 0;
        for (Object key : keys)
            if (ImageBufferCache.get(key) != null)
                held++;
        return held;
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) {
            Thread.sleep(20);
        }
    }

    private ImageBufferCacheBudgetCheck() {}

}
