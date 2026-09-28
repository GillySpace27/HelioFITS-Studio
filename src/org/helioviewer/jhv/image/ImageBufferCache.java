package org.helioviewer.jhv.image;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

public final class ImageBufferCache {

    /**
     * 40% of physical memory, and never less than 4 GiB.
     *
     * <p>The decoded frames are native memory (MemoryUtil), outside the Java heap, so what bounds
     * them is the machine, not -Xmx. A fixed 8 GiB held about a quarter of one 983-frame PUNCH CAM
     * movie (4096 x 4096 half floats, 32 MiB a frame), so every loop decoded every frame again.
     */
    private static final long MAX_CACHE_BYTES = cacheBytes();

    private static long cacheBytes() {
        long physical = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        long bytes = Math.max(4L << 30, (long) (physical * 0.4));
        org.helioviewer.jhv.app.Log.info("Decoded-frame cache: up to " + (bytes >> 30) + " GiB of " + (physical >> 30) + " GiB physical memory");
        return bytes;
    }
    private static final ArrayList<WeakReference<ImageBuffer>> retired = new ArrayList<>();

    private static final Cache<Object, DecodedImage> cache = Caffeine.newBuilder()
            .maximumWeight(MAX_CACHE_BYTES)
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
