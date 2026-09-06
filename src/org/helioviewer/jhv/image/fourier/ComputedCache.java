package org.helioviewer.jhv.image.fourier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.system.MemoryUtil;

/**
 * The frames a sequence filter computed, kept on disk between runs and served from there as
 * memory-mapped files.
 *
 * <p>A restored session used to re-run every filter it carried, 25 s for a 245-frame PUNCH movie,
 * because the output lived only in native memory owned by the view. Now a finished run is written
 * under Cache/fourier/&lt;key&gt;/, one raw half-float file per frame and a meta.json, and the next
 * run of the same filter over the same frames maps those files straight in as the frame buffers.
 * Mapping also changes what the 8.2 GB of output costs while the filter is on: it is the OS's page
 * cache rather than pinned native heap, and the OS pages it as the movie plays.
 *
 * <p>The key is a digest of the filter's parameters and of every source frame's identity and time
 * (View.frameKey), so a different band, a different span, or a re-downloaded file is a different
 * entry. A source whose frames have no stable identity (a stream) is not cached. Bump VERSION when
 * a job's output changes for the same parameters.
 *
 * <p>A frame's region and physical scale are in meta.json, so a hit touches no source frame,
 * except for a scale that was the source frame's own (a notch, the noise gate), which is read back
 * from that frame. The presence of meta.json is what marks an entry complete; it is written last.
 */
public final class ComputedCache {

    private static final int VERSION = 1;
    // ponytail: one fixed cap, oldest entry first; a setting if disks get tight
    private static final long CAP_BYTES = 32L << 30;
    private static final String META = "meta.json";

    /** What a run produced or a hit mapped: the frames, and the spectrum the job gathered, if any. */
    public record Hit(DecodedImage[] frames, @Nullable FourierFilter.Spectrum spectrum) {}

    private static Path root() {
        return Path.of(Directories.CACHE.getPath(), "fourier");
    }

    @Nullable
    static String key(View source, SequenceParams params) {
        int n = source.getMaximumFrameNumber() + 1;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("v" + VERSION + '|' + params.toJson() + '|' + n).getBytes(StandardCharsets.UTF_8));
            for (int k = 0; k < n; k++) {
                String id = source.frameKey(k);
                if (id == null)
                    return null;
                md.update((id + '@' + source.getFrameTime(k).milli + ';').getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** An earlier run of exactly this filter over exactly these frames, mapped from disk; null when there is none. */
    @Nullable
    public static Hit load(View source, SequenceParams params) {
        String key = key(source, params);
        if (key == null)
            return null;
        Path dir = root().resolve(key);
        Path metaPath = dir.resolve(META);
        if (!Files.isRegularFile(metaPath))
            return null;
        try {
            JSONObject meta = new JSONObject(Files.readString(metaPath));
            JSONArray fs = meta.getJSONArray("frames");
            int n = source.getMaximumFrameNumber() + 1;
            if (meta.optInt("version") != VERSION || fs.length() != n)
                return null;
            DecodedImage[] out = new DecodedImage[n];
            for (int k = 0; k < n; k++) {
                JSONObject f = fs.getJSONObject(k);
                int w = f.getInt("w"), h = f.getInt("h");
                Path file = dir.resolve(name(k));
                long bytes = (long) w * h * ImageBuffer.Format.Gray16F.bytes;
                if (Files.size(file) != bytes)
                    return null;
                ByteBuffer mapped;
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                    mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, bytes);
                }
                ImageBuffer buffer = ImageBuffer.mapped(w, h, ImageBuffer.Format.Gray16F, mapped);
                buffer.setPhysicalScale(scale(f.optJSONObject("scale"), source, k));
                JSONArray r = f.getJSONArray("region");
                out[k] = new DecodedImage(buffer, new Region(r.getDouble(0), r.getDouble(1), r.getDouble(2), r.getDouble(3)));
            }
            Files.setLastModifiedTime(metaPath, FileTime.fromMillis(System.currentTimeMillis())); // most recently used
            return new Hit(out, spectrumFrom(meta.optJSONObject("spectrum")));
        } catch (Exception e) {
            Log.warn("Computed-frame cache unreadable, recomputing: " + dir, e);
            return null;
        }
    }

    /**
     * Write a finished run to disk and hand back the same frames mapped from those files. On any
     * failure the entry is removed and the frames come back as they were, so a full disk costs the
     * cache and nothing else.
     */
    public static Hit store(View source, SequenceParams params, DecodedImage[] frames, @Nullable FourierFilter.Spectrum spectrum) {
        Hit asIs = new Hit(frames, spectrum);
        String key = key(source, params);
        if (key == null)
            return asIs;
        for (DecodedImage f : frames)
            if (f == null || f.imageBuffer().format != ImageBuffer.Format.Gray16F)
                return asIs;
        Path dir = root().resolve(key);
        try {
            deleteTree(dir); // a stale or half-written entry under the same key
            Files.createDirectories(dir);
            JSONArray fs = new JSONArray();
            for (int k = 0; k < frames.length; k++) {
                ImageBuffer b = frames[k].imageBuffer();
                ByteBuffer bytes = MemoryUtil.memByteBuffer(MemoryUtil.memAddress0((ShortBuffer) b.buffer), b.byteSize());
                try (FileChannel ch = FileChannel.open(dir.resolve(name(k)), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    while (bytes.hasRemaining())
                        ch.write(bytes);
                }
                Region r = frames[k].region();
                JSONObject f = new JSONObject().put("w", b.width).put("h", b.height)
                        .put("region", new JSONArray().put(r.llx).put(r.lly).put(r.width).put(r.height));
                ImageBuffer.PhysicalScale s = b.physicalScale();
                if (s != null)
                    f.put("scale", s.stretch().startsWith("Y = t") // the identity stretch the packers write; anything else is the source frame's own
                            ? new JSONObject().put("kind", "identity").put("min", s.min()).put("max", s.max()).put("stretch", s.stretch())
                            : new JSONObject().put("kind", "source"));
                fs.put(f);
            }
            JSONObject meta = new JSONObject().put("version", VERSION).put("params", params.toJson()).put("frames", fs);
            if (spectrum != null)
                meta.put("spectrum", spectrumJson(spectrum));
            Files.writeString(dir.resolve(META), meta.toString()); // last: its presence means complete
        } catch (Exception e) {
            Log.warn("Could not cache the computed frames under " + dir, e);
            deleteTree(dir);
            return asIs;
        }
        evict(dir);
        Hit mapped = load(source, params);
        return mapped != null ? mapped : asIs;
    }

    @Nullable
    private static ImageBuffer.PhysicalScale scale(@Nullable JSONObject js, View source, int k) {
        if (js == null)
            return null;
        if ("source".equals(js.optString("kind"))) {
            DecodedImage src = source.frameImage(k); // the frame's own stretch, as packLike used it
            return src == null ? null : src.imageBuffer().physicalScale();
        }
        return new ImageBuffer.PhysicalScale((float) js.getDouble("min"), (float) js.getDouble("max"), y -> y, js.optString("stretch", "Y = t"), y -> y);
    }

    private static JSONObject spectrumJson(FourierFilter.Spectrum s) {
        return new JSONObject().put("kind", s.kind().name()).put("rate", new JSONArray(s.rate()))
                .put("positive", new JSONArray(s.powerPositive())).put("negative", new JSONArray(s.powerNegative()));
    }

    @Nullable
    private static FourierFilter.Spectrum spectrumFrom(@Nullable JSONObject js) {
        if (js == null)
            return null;
        try {
            return new FourierFilter.Spectrum(FourierParams.Kind.valueOf(js.getString("kind")),
                    doubles(js.getJSONArray("rate")), doubles(js.getJSONArray("positive")), doubles(js.getJSONArray("negative")));
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] doubles(JSONArray ja) {
        double[] d = new double[ja.length()];
        for (int i = 0; i < d.length; i++)
            d[i] = ja.getDouble(i);
        return d;
    }

    // Least recently used first, never the entry just written. An entry without meta.json (a run
    // that died mid-write) dates from the epoch, so it goes first.
    private static void evict(Path keep) {
        try (Stream<Path> dirs = Files.list(root())) {
            List<Path> entries = dirs.filter(Files::isDirectory).filter(d -> !d.equals(keep))
                    .sorted(Comparator.comparingLong(ComputedCache::used)).toList();
            long total = size(keep) + entries.stream().mapToLong(ComputedCache::size).sum();
            for (Path d : entries) {
                if (total <= CAP_BYTES)
                    break;
                total -= size(d);
                deleteTree(d);
                Log.info("Computed-frame cache: evicted " + d.getFileName());
            }
        } catch (IOException e) {
            Log.warn(e);
        }
    }

    private static long used(Path dir) {
        try {
            return Files.getLastModifiedTime(dir.resolve(META)).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long size(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir))
            return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                }
            });
        } catch (IOException ignore) {
        }
    }

    /** Remove the entry for this filter over these frames, if any. For the check, which must not leave its frames behind. */
    static void forget(View source, SequenceParams params) {
        String key = key(source, params);
        if (key != null)
            deleteTree(root().resolve(key));
    }

    private static String name(int k) {
        return String.format("%04d.f16", k);
    }

    private ComputedCache() {}

}
