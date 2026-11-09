package org.helioviewer.jhv.view.uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.metadata.Region;
import org.lwjgl.system.MemoryUtil;

/**
 * Streaming a dataset bigger than the machine's memory: decoded frames kept on disk and served as
 * memory-mapped files, with a small window of them held in memory.
 *
 * <p>A FITS frame from the HelioFITS Core library is Rice tile-compressed, so the file itself
 * cannot be mapped; mapping only helps the decoded frame. A frame whose layer was loaded with
 * "Stream (memory-mapped)" is therefore decoded once, written under Cache/stream/ as raw half
 * floats (one file per frame, so the 2 GiB limit of one MappedByteBuffer never applies), and every
 * later request maps that file. The OS pages the mapping in and out; Studio's own memory for these
 * frames is only the window, {@value #KEY_MB} MB by default, instead of the decoded-frame cache.
 *
 * <p>What it does not do: a changed display range or scaling is a different frame (the stretch is
 * baked into the half floats), so it decodes once more under a new name; the old file ages out
 * under the disk budget, {@value #KEY_GB} GB by default, oldest first. A frame that is not a plain
 * half-float FITS frame (a filter is on, a LASCO background was taken off, it is 8-bit, or it is
 * provisional) is not stored and goes through the ordinary cache. The streaming mark lasts for one
 * launch.
 */
public final class StreamStore {

    public static final String KEY_MB = "stream.memoryMB";
    public static final String KEY_GB = "stream.diskGB";
    public static final String KEY_CHOICE = "stream.choice"; // "ask" (default), "all" or "stream"
    public static final int DEFAULT_MB = 2048;
    public static final int DEFAULT_GB = 100;

    private static final int MAGIC = 0x53545231; // "STR1"
    private static final int HEADER = 64; // a multiple of 2, so the half floats stay aligned in the map
    private static final String EXT = ".f16";
    private static final int TRIM_EVERY = 32; // writes between looks at the folder size

    private static final Set<String> STREAMING = ConcurrentHashMap.newKeySet();
    private static final Window<Object, DecodedImage> WINDOW = new Window<>(StreamStore::windowBytes);
    private static final AtomicInteger WRITES = new AtomicInteger();

    private StreamStore() {}

    // ---- the mark -------------------------------------------------------------------------

    public static void mark(Collection<File> files) {
        for (File f : files)
            STREAMING.add(f.getAbsolutePath());
    }

    static boolean isStreaming(File file) {
        return !STREAMING.isEmpty() && STREAMING.contains(file.getAbsolutePath());
    }

    // ---- settings -------------------------------------------------------------------------

    public static long windowBytes() {
        return setting(KEY_MB, DEFAULT_MB) << 20;
    }

    public static long diskBytes() {
        return setting(KEY_GB, DEFAULT_GB) << 30;
    }

    private static long setting(String key, int fallback) {
        try {
            long v = Long.parseLong(Settings.getProperty(key).trim());
            return v > 0 ? v : fallback;
        } catch (RuntimeException e) { // unset or not a number
            return fallback;
        }
    }

    // ---- the in-memory window -------------------------------------------------------------

    @Nullable
    static DecodedImage windowGet(Object key) {
        return WINDOW.get(key);
    }

    static void windowPut(Object key, DecodedImage image) {
        WINDOW.put(key, image, image.imageBuffer().byteSize());
    }

    static void windowDrop(Predicate<Object> which) {
        WINDOW.removeIf(which);
    }

    /** A least-recently-used map bounded by bytes, not entries. Always keeps the newest entry, however large. */
    static final class Window<K, V> {
        private final LinkedHashMap<K, V> map = new LinkedHashMap<>(16, 0.75f, true);
        private final Map<K, Long> sizes = new java.util.HashMap<>();
        private final java.util.function.LongSupplier budget;
        private long held;

        Window(java.util.function.LongSupplier _budget) {
            budget = _budget;
        }

        synchronized V get(K key) {
            return map.get(key);
        }

        synchronized void put(K key, V value, long bytes) {
            map.put(key, value);
            Long old = sizes.put(key, bytes);
            held += bytes - (old == null ? 0 : old);
            var it = map.entrySet().iterator();
            while (held > budget.getAsLong() && map.size() > 1) {
                var eldest = it.next();
                held -= sizes.remove(eldest.getKey());
                it.remove();
            }
        }

        synchronized void removeIf(Predicate<K> which) {
            var it = map.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (which.test(e.getKey())) {
                    held -= sizes.remove(e.getKey());
                    it.remove();
                }
            }
        }

        synchronized long held() {
            return held;
        }

        synchronized int size() {
            return map.size();
        }
    }

    // ---- the disk store -------------------------------------------------------------------

    static Path root() {
        return Path.of(Directories.CACHE.getPath(), "stream");
    }

    /** The file name of one decoded frame: everything that changes its pixels is in the digest. */
    static String id(File file, String parameters) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((file.getAbsolutePath() + '|' + file.length() + '|' + file.lastModified() + '|' + parameters)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()) + EXT;
        } catch (java.security.NoSuchAlgorithmException e) { // SHA-1 is always there
            throw new IllegalStateException(e);
        }
    }

    /** A stored frame mapped from disk, or null when there is none or it is not whole. */
    @Nullable
    static DecodedImage read(Path file, Region region, ImageProcessingSettings.FITSParameters state) {
        if (!Files.isRegularFile(file))
            return null;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(HEADER);
            while (head.hasRemaining())
                if (ch.read(head) < 0)
                    return null;
            head.flip();
            if (head.getInt() != MAGIC)
                return null;
            int w = head.getInt(), h = head.getInt();
            float min = head.getFloat(), max = head.getFloat();
            long bytes = (long) w * h * ImageBuffer.Format.Gray16F.bytes;
            if (w <= 0 || h <= 0 || bytes > Integer.MAX_VALUE || ch.size() != HEADER + bytes) // one frame per file keeps it under 2 GiB; a torn file is refused
                return null;
            ByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, HEADER, bytes);
            ImageBuffer buffer = ImageBuffer.mapped(w, h, ImageBuffer.Format.Gray16F, mapped);
            float span = max - min;
            buffer.setPhysicalScale(new ImageBuffer.PhysicalScale(min, max, FITSData.inverseMapping(state, span),
                    FITSData.stretchName(state, span), FITSData.normalizedMapping(state, span)));
            try {
                Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis())); // most recently used
            } catch (IOException ignored) { // a read-only folder still serves
            }
            return DecodedImage.measured(buffer, region);
        } catch (IOException | RuntimeException e) {
            Log.warn("Streamed frame unreadable, decoding again: " + file, e);
            return null;
        }
    }

    /** Whether a decoded frame can be stored: plain half floats in native memory, nothing the stored copy would lose. */
    static boolean storable(ImageBuffer b) {
        return b.format == ImageBuffer.Format.Gray16F && b.physicalScale() != null && !b.isProvisional()
                && b.background() == null && !b.isBackgroundSubtracted() && b.buffer instanceof ShortBuffer s && s.isDirect();
    }

    /**
     * Write a frame and hand back the same frame mapped from the file. On any failure, a full disk
     * included, the frame comes back as it was and the cost is the cache, nothing else.
     */
    static DecodedImage store(Path dir, String name, DecodedImage image, Region region, ImageProcessingSettings.FITSParameters state) {
        if (!write(dir, name, image))
            return image;
        DecodedImage mapped = read(dir.resolve(name), region, state);
        return mapped != null ? mapped : image;
    }

    private static boolean write(Path dir, String name, DecodedImage image) {
        ImageBuffer b = image.imageBuffer();
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = dir.resolve(name + '.' + Thread.currentThread().threadId() + ".tmp");
            ImageBuffer.PhysicalScale scale = b.physicalScale();
            ByteBuffer head = ByteBuffer.allocate(HEADER);
            head.putInt(MAGIC).putInt(b.width).putInt(b.height).putFloat(scale.min()).putFloat(scale.max()).flip();
            ByteBuffer pixels = MemoryUtil.memByteBuffer(MemoryUtil.memAddress0((ShortBuffer) b.buffer), b.byteSize());
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer pad = ByteBuffer.allocate(HEADER - head.remaining());
                while (head.hasRemaining())
                    ch.write(head);
                while (pad.hasRemaining())
                    ch.write(pad);
                while (pixels.hasRemaining())
                    ch.write(pixels);
            }
            Path file = dir.resolve(name);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            if (WRITES.incrementAndGet() % TRIM_EVERY == 0)
                trim(dir, diskBytes());
            return true;
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not store a streamed frame under " + dir, e);
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) { // a stale temp is harmless
                }
            }
        }
    }

    /** One stored frame as the budget sees it. */
    record Entry(String name, long modified, long bytes) {}

    /** The names to remove so the folder fits {@code max} bytes: oldest first, and never the newest entry. */
    static List<String> victims(List<Entry> entries, long max) {
        List<Entry> byAge = new ArrayList<>(entries);
        byAge.sort(Comparator.comparingLong(Entry::modified));
        long total = 0;
        for (Entry e : byAge)
            total += e.bytes();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < byAge.size() - 1 && total > max; i++) {
            out.add(byAge.get(i).name());
            total -= byAge.get(i).bytes();
        }
        return out;
    }

    /** Keep the folder within its budget. These are cache files this class wrote, and only those are touched. */
    static void trim(Path dir, long max) {
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator)
                if (p.getFileName().toString().endsWith(EXT))
                    entries.add(new Entry(p.getFileName().toString(), Files.getLastModifiedTime(p).toMillis(), Files.size(p)));
        } catch (IOException e) {
            return;
        }
        for (String name : victims(entries, max)) {
            try {
                Files.deleteIfExists(dir.resolve(name));
            } catch (IOException ignored) { // still mapped on some systems; the next trim tries again
            }
        }
    }
}
