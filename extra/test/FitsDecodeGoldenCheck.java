package org.helioviewer.jhv.view.uri;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONObject;

/**
 * Every FITS fixture in extra/test/data decodes to the numbers it decoded to when it was pinned.
 *
 * <p>Per fixture: width, height, BITPIX, how many values are NaN, and a 64-bit FNV-1a hash of the
 * decoded values as float32 (BZERO + BSCALE * raw, BLANK as NaN, every NaN written as one pattern),
 * little-endian, in the order FITSImage.readData returns them. One changed value changes the hash.
 * A decode that changes on purpose changes it too: rerun with -Dgolden.update=true, which rewrites
 * extra/test/data/decode-golden.json and prints every entry that moved, and say why in the commit.
 *
 * <p>No fixture is a LASCO frame (TELESCOP SOHO), so readData never asks NRL for a background here;
 * a LASCO fixture added later would need the network.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" [-Dgolden.update=true] org.helioviewer.jhv.view.uri.FitsDecodeGoldenCheck
 */
public final class FitsDecodeGoldenCheck {

    private static final Path DATA = Path.of("extra/test/data");
    private static final Path GOLDEN = DATA.resolve("decode-golden.json");
    private static final int BAD_PIXEL = Integer.MIN_VALUE; // FITSImage's sentinel after a background subtraction

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    record Entry(int width, int height, int bitpix, int nanCount, String hash) {
        String json() {
            return String.format("{\"width\": %d, \"height\": %d, \"bitpix\": %d, \"nanCount\": %d, \"hash\": \"%s\"}",
                    width, height, bitpix, nanCount, hash);
        }

        static Entry of(JSONObject o) {
            return new Entry(o.getInt("width"), o.getInt("height"), o.getInt("bitpix"), o.getInt("nanCount"), o.getString("hash"));
        }
    }

    static Entry decode(File file) throws Exception {
        FITSData d = FITSImage.readData(file, 0);
        float[] values = physical(d);
        int nan = 0;
        long hash = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis
        for (float v : values) {
            if (Float.isNaN(v))
                nan++;
            int bits = Float.floatToIntBits(v);
            for (int k = 0; k < 4; k++) {
                hash ^= (bits >>> (8 * k)) & 0xFF;
                hash *= 0x100000001b3L; // FNV-1a 64-bit prime
            }
        }
        return new Entry(d.width(), d.height(), d.header().getIntValue("BITPIX", 0), nan, String.format("%016x", hash));
    }

    /** The decoded values as the rest of the reader sees them; a byte image as its unsigned bytes, as FITSData.decode copies them. */
    static float[] physical(FITSData d) {
        Object p = d.pixels();
        int n = d.width() * d.height();
        float[] out = new float[n];
        switch (p) {
            case byte[] a -> {
                for (int i = 0; i < n; i++)
                    out[i] = a[i] & 0xFF;
            }
            case short[] a -> {
                for (int i = 0; i < n; i++)
                    out[i] = d.hasBlank() && a[i] == (short) d.blank() ? Float.NaN : (float) (d.bzero() + a[i] * d.bscale());
            }
            case int[] a -> {
                for (int i = 0; i < n; i++)
                    out[i] = d.hasBlank() && a[i] == (int) d.blank() ? Float.NaN : (float) (d.bzero() + a[i] * d.bscale());
            }
            case long[] a -> {
                for (int i = 0; i < n; i++)
                    out[i] = d.hasBlank() && a[i] == d.blank() ? Float.NaN : (float) (d.bzero() + a[i] * d.bscale());
            }
            case float[] a -> {
                for (int i = 0; i < n; i++)
                    out[i] = a[i] == BAD_PIXEL ? Float.NaN : (float) (d.bzero() + a[i] * d.bscale());
            }
            case double[] a -> {
                for (int i = 0; i < n; i++)
                    out[i] = (float) (d.bzero() + a[i] * d.bscale());
            }
            default -> throw new IllegalStateException("unexpected pixel type " + p.getClass().getSimpleName());
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        boolean update = Boolean.getBoolean("golden.update");
        File[] fixtures = DATA.toFile().listFiles((dir, name) -> name.endsWith(".fits") || name.endsWith(".fts"));
        expect("FITS fixtures found under " + DATA.toAbsolutePath(), fixtures != null && fixtures.length > 0);
        if (fixtures == null || fixtures.length == 0)
            System.exit(1);

        Map<String, Entry> now = new TreeMap<>();
        for (File f : fixtures) {
            try {
                now.put(f.getName(), decode(f));
            } catch (Exception e) {
                expect(f.getName() + " decodes (" + e + ")", false);
            }
        }

        Map<String, Entry> golden = new TreeMap<>();
        if (Files.isRegularFile(GOLDEN)) {
            JSONObject g = new JSONObject(Files.readString(GOLDEN, StandardCharsets.UTF_8));
            for (String name : g.keySet())
                golden.put(name, Entry.of(g.getJSONObject(name)));
        }

        if (update) {
            for (String name : golden.keySet())
                if (!now.containsKey(name))
                    System.out.println("  removed " + name + ": " + golden.get(name).json());
            for (Map.Entry<String, Entry> e : now.entrySet()) {
                Entry old = golden.get(e.getKey());
                if (old == null)
                    System.out.println("  added   " + e.getKey() + ": " + e.getValue().json());
                else if (!old.equals(e.getValue()))
                    System.out.println("  changed " + e.getKey() + ": " + old.json() + " -> " + e.getValue().json());
            }
            StringBuilder sb = new StringBuilder("{\n");
            int k = 0;
            for (Map.Entry<String, Entry> e : now.entrySet())
                sb.append("  ").append(JSONObject.quote(e.getKey())).append(": ").append(e.getValue().json())
                        .append(++k < now.size() ? ",\n" : "\n");
            Files.writeString(GOLDEN, sb.append("}\n"), StandardCharsets.UTF_8);
            System.out.println("wrote " + GOLDEN + " (" + now.size() + " fixtures)");
        } else {
            expect("the golden file exists: " + GOLDEN + " (create it once with -Dgolden.update=true)", !golden.isEmpty());
            for (String name : golden.keySet())
                if (!now.containsKey(name))
                    expect(name + " is pinned in decode-golden.json but is gone or no longer decodes", false);
            for (Map.Entry<String, Entry> e : now.entrySet()) {
                Entry want = golden.get(e.getKey());
                if (want == null && !golden.isEmpty())
                    expect(e.getKey() + " has a golden entry (a new fixture: rerun with -Dgolden.update=true)", false);
                else if (want != null)
                    expect(e.getKey() + ": " + e.getValue().json() + (want.equals(e.getValue()) ? "" : " but golden is " + want.json()),
                            want.equals(e.getValue()));
            }
        }

        if (failures != 0) {
            System.out.println(failures + " decode golden failure(s)");
            System.exit(1);
        }
    }

}
