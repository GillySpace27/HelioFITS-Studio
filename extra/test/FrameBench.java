package org.helioviewer.jhv.view.uri;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.io.CacheIndex;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.NetFileCache;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.thread.LatestWorker;
import org.helioviewer.jhv.view.ClipSet;
import org.lwjgl.system.MemoryUtil;

/**
 * The decode path's contract, that a speed-up must not change a single value, as something that
 * can fail; plus a timer for each stage of a decode. Over real files in this machine's download
 * cache, which is why it is not a Check: CI has no such cache.
 *
 * <p>Record a baseline before touching anything between a file and its decoded frame, then verify
 * after. The baseline lists the files it used, so a verify decodes exactly those again, through
 * FITSImage.readData and FITSData.decode as the app does, once per filter. Each decoded frame is
 * hashed (SHA-256 of its buffer) along with its size, format and clip range. Exit 1 on any
 * difference.
 *
 * <pre>
 *   ant jar && java -cp "bin:extra/test-classes:resources:lib/*:lib/**" org.helioviewer.jhv.view.uri.FrameBench --record
 *   java ... org.helioviewer.jhv.view.uri.FrameBench            (verify, and time each stage)
 *   java ... org.helioviewer.jhv.view.uri.FrameBench --reps 5   (median of five for the timings)
 * </pre>
 *
 * <p>The corpus is two frames (first and middle) of every dataset CacheIndex finds in the cache.
 * A LASCO frame whose monthly background could not be fetched comes out provisional; one that is
 * provisional on only one side of a comparison is reported as not comparable rather than as a
 * difference.
 */
public final class FrameBench {

    private static final int PER_DATASET = 2;
    private static final ImageFilter.Type[] FILTERS = ImageFilter.Type.values();

    public static void main(String[] args) throws Exception {
        List<String> argv = List.of(args);
        boolean record = argv.contains("--record");
        int reps = argv.contains("--reps") ? Integer.parseInt(argv.get(argv.indexOf("--reps") + 1)) : 1;

        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        org.helioviewer.jhv.app.Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        Path baseline = Path.of(Directories.HOME.getPath(), "bench", "frame-identity.tsv");
        Map<String, String> expected = new HashMap<>();
        List<File> files = new ArrayList<>();
        Map<File, String> names = new HashMap<>(); // file on disk now -> name the baseline recorded
        if (record) {
            files = corpus();
        } else {
            if (!Files.isRegularFile(baseline)) {
                System.out.println("No baseline at " + baseline + ": run with --record first.");
                System.exit(2);
            }
            LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
            for (String line : Files.readAllLines(baseline)) {
                if (line.startsWith("#") || line.isBlank())
                    continue;
                String[] f = line.split("\t");
                expected.put(f[0] + "\t" + f[1], line);
                seen.put(f[0], true);
            }
            for (String name : seen.keySet()) {
                File file = resolve(name);
                files.add(file);
                names.put(file, name);
            }
        }

        Field regionField = URIView.class.getDeclaredField("imageRegion");
        regionField.setAccessible(true);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");

        List<String> out = new ArrayList<>();
        int different = 0, incomparable = 0, missing = 0, compared = 0;
        System.out.printf("%-58s %7s %7s %7s %7s %7s   (median ms of %d)%n", "file", "read", FILTERS[0], FILTERS[1], FILTERS[2], FILTERS[3], reps);
        for (File file : files) {
            if (!file.isFile()) {
                System.out.println("missing: " + file.getName());
                missing++;
                continue;
            }
            String recorded = record ? file.getName() : names.get(file);
            ImageProcessingSettings ps = new ImageProcessingSettings(() -> {});
            URIView view = new URIView(new LatestWorker<>("bench"), NetFileCache.get(file.toURI()), ps);
            Region region = (Region) regionField.get(view);
            MetaData meta = view.getMetaData(null);
            ClipSet.Range range = ps.fitsParameters().clipRange(view.getClipSet());

            double[] readMs = new double[reps * FILTERS.length];
            StringBuilder row = new StringBuilder();
            for (int k = 0; k < FILTERS.length; k++) {
                ImageFilter.Type type = FILTERS[k];
                ImageFilter filter = ImageFilter.of(type, region, meta);
                double[] decodeMs = new double[reps];
                ImageBuffer image = null;
                for (int r = 0; r < reps; r++) {
                    long t0 = System.nanoTime();
                    FITSData data = FITSImage.readData(file, ps.fitsParameters().plane()); // fresh each time, as the app does
                    long t1 = System.nanoTime();
                    image = data.decode(filter, ps.fitsParameters(), range);
                    long t2 = System.nanoTime();
                    readMs[k * reps + r] = (t1 - t0) / 1e6;
                    decodeMs[r] = (t2 - t1) / 1e6;
                }
                row.append(String.format(" %7.0f", median(decodeMs)));

                ByteBuffer bytes = image.buffer instanceof ShortBuffer s ? MemoryUtil.memByteBuffer(s) : (ByteBuffer) image.buffer;
                sha.update(bytes.duplicate());
                String line = String.join("\t", recorded, type.name(), String.valueOf(image.width), String.valueOf(image.height),
                        image.format.name(), range == null ? "-" : range.lower() + ".." + range.upper(),
                        image.isProvisional() ? "provisional" : "final", HexFormat.of().formatHex(sha.digest()));
                out.add(line);

                if (!record) {
                    String was = expected.get(recorded + "\t" + type.name());
                    if (was == null)
                        continue;
                    compared++;
                    boolean provisionalMismatch = !was.split("\t")[6].equals(line.split("\t")[6]);
                    if (provisionalMismatch) {
                        incomparable++;
                        System.out.println("  not comparable (LASCO background availability changed): " + file.getName() + " " + type);
                    } else if (!was.equals(line)) {
                        different++;
                        System.out.println("  DIFFERENT " + file.getName() + " " + type + "\n    was " + was + "\n    now " + line);
                    }
                }
                image = null;
            }
            System.out.printf("%-58s %7.0f%s%n", file.getName().length() > 58 ? file.getName().substring(0, 58) : file.getName(), median(readMs), row);
            System.gc(); // the frames are native memory, freed by a Cleaner once unreachable; nudge it along
        }

        if (record) {
            Files.createDirectories(baseline.getParent());
            List<String> text = new ArrayList<>();
            text.add("# FrameBench baseline: file, filter, width, height, format, clip range, provisional, sha256 of the decoded buffer");
            text.addAll(out);
            Files.write(baseline, text);
            System.out.println("Recorded " + out.size() + " decoded frames from " + files.size() + " files to " + baseline);
            System.exit(0);
        }
        System.out.println(compared + " decoded frames compared, " + different + " different, " + incomparable + " not comparable, " + missing + " files missing");
        System.out.println(different == 0 ? "FrameBench: IDENTICAL" : "FrameBench: VALUES CHANGED");
        System.exit(different == 0 ? 0 : 1);
    }

    // Two frames of every dataset, first and middle, in a fixed order.
    private static List<File> corpus() {
        List<CacheIndex.Dataset> sets = new ArrayList<>(CacheIndex.group(CacheIndex.scan(null)));
        sets.sort(Comparator.comparing(CacheIndex.Dataset::key));
        List<File> files = new ArrayList<>();
        for (CacheIndex.Dataset set : sets) {
            List<File> all = CacheIndex.files(set);
            List<CacheIndex.Frame> frames = new ArrayList<>(set.frames());
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++)
                order.add(i);
            order.sort(Comparator.comparingLong(i -> frames.get(i).time()));
            if (!order.isEmpty())
                files.add(all.get(order.get(0)));
            if (PER_DATASET > 1 && order.size() > 1)
                files.add(all.get(order.get(order.size() / 2)));
        }
        return files;
    }

    // A file recorded under its old bare-digest name may since have been renamed on first use to
    // <name>_<first 12 hex of the digest>.<ext> (NetFileCache.readableName); follow it there.
    private static File resolve(String name) {
        File dir = Directories.FILECACHE.getFile();
        File file = new File(dir, name);
        if (file.isFile() || !name.matches("[0-9a-f]{64}"))
            return file;
        String tag = "_" + name.substring(0, 12);
        File[] renamed = dir.listFiles((d, n) -> n.contains(tag + ".") || n.endsWith(tag));
        return renamed != null && renamed.length == 1 ? renamed[0] : file;
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private FrameBench() {}
}
