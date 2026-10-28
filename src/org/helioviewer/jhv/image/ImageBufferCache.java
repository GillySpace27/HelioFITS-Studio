package org.helioviewer.jhv.image;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import org.helioviewer.jhv.app.MemoryBudget;

public final class ImageBufferCache {

    /**
     * The decoded frames' part of the memory share (MemoryBudget, 50% of physical memory by
     * default, a Setting), which is three quarters of it.
     *
     * <p>The decoded frames are native memory (MemoryUtil), outside the Java heap, so what bounds
     * them is the machine, not -Xmx. A fixed 8 GiB held about a quarter of one 983-frame PUNCH CAM
     * movie (4096 x 4096 half floats, 32 MiB a frame), so every loop decoded every frame again. It
     * used to be 40% of physical memory on its own, which together with the heap outgrew the machine
     * and went to swap, so it now shares one budget with the JPIP bins.
     */
    private static long cacheBytes() {
        long bytes = MemoryBudget.decodedBytes();
        org.helioviewer.jhv.app.Log.info("Decoded-frame cache: up to " + (bytes >> 30) + " GiB, " + MemoryBudget.sharePercent()
                + "% of " + (MemoryBudget.physical() >> 30) + " GiB physical memory shared with the JPIP data");
        return bytes;
    }

    /** The share changed in Settings: resize the cache now; frames over the new size are evicted. */
    public static void applyBudget() {
        setMaximum(cacheBytes());
    }

    static void setMaximum(long bytes) {
        cache.policy().eviction().ifPresent(eviction -> eviction.setMaximum(bytes));
    }

    /** What evicted frames still hold until the next reap frees them, for the budget to see. */
    public static long retiredCount() {
        synchronized (retired) {
            return retired.size();
        }
    }
    private static final ArrayList<WeakReference<ImageBuffer>> retired = new ArrayList<>();

    private static final Cache<Object, DecodedImage> cache = Caffeine.newBuilder()
            .maximumWeight(cacheBytes())
            .weigher((Object key, DecodedImage value) -> value.imageBuffer().byteSize())
            .removalListener((Object key, DecodedImage value, RemovalCause cause) -> retire(value.imageBuffer()))
            .build();

    @Nullable
    public static DecodedImage get(Object key) {
        return cache.getIfPresent(key);
    }

    public static void put(Object key, DecodedImage image) {
        cache.put(key, image);
    }

    public static void invalidateIf(Predicate<Object> predicate) {
        cache.asMap().keySet().removeIf(predicate);
    }

    private static void retire(ImageBuffer imageBuffer) { // we are using strong values.
        synchronized (retired) {
            retired.add(new WeakReference<>(imageBuffer));
        }
    }

    public static void reap(Set<ImageBuffer> retained) {
        cache.cleanUp();
        synchronized (retired) {
            retired.removeIf(reference -> {
                ImageBuffer imageBuffer = reference.get();
                return imageBuffer == null || (!retained.contains(imageBuffer) && imageBuffer.free());
            });
        }
    }

    private ImageBufferCache() {}
}
