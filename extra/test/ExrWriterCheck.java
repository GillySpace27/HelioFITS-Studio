package org.helioviewer.jhv.movie;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

/**
 * ExrWriter has to produce a file that other software agrees is OpenEXR, so this reads its own
 * output back with an independent parser (header walk, offset table, inflate, un-predict,
 * un-reorder, half to float) and compares every pixel, then hands the file to the macOS system
 * decoder, the one that refused ffmpeg's files, when it is available.
 *
 * <p>The image is 37x23: not a multiple of 16 lines, so the last chunk is partial, and an odd
 * byte count per chunk, which is the case the reorder's front/back split gets wrong if the
 * rounding is off by one. Values span the half range, including ones a half cannot hold
 * exactly, so the comparison is against what half rounds them to.
 *
 * <p>Run: java -cp bin:extra/test-classes:lib/* org.helioviewer.jhv.movie.ExrWriterCheck
 */
public final class ExrWriterCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        int w = 37, h = 23;
        float[] r = new float[w * h], g = new float[w * h], y = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            r[i] = i / (float) (w * h);          // a ramp
            g[i] = 1 - r[i];                     // the other way
            y[i] = (float) Math.sin(i * 0.37) * 1234.5f; // signed, large, not representable
        }
        ExrWriter exr = new ExrWriter(w, h);
        exr.channel("R", r);
        exr.channel("G", g);
        exr.channel("AIA171.Y", y);
        exr.attribute("jhv", "{\"check\":true}");
        exr.rational("framesPerSecond", 24, 1);
        exr.attribute("utcOffset", 0f);
        exr.attribute("frame", 7);

        File file = File.createTempFile("exrcheck", ".exr");
        file.deleteOnExit();
        exr.write(file);

        Parsed p = parse(Files.readAllBytes(file.toPath()));
        expect("magic", p.magic == 20000630);
        expect("version 2, no flags", p.version == 2);
        expect("all eight required attributes", p.attributes.containsAll(List.of("channels", "compression", "dataWindow",
                "displayWindow", "lineOrder", "pixelAspectRatio", "screenWindowCenter", "screenWindowWidth")));
        expect("custom attributes carried", p.attributes.containsAll(List.of("jhv", "framesPerSecond", "utcOffset", "frame")));
        expect("channels sorted", p.channelNames.equals(List.of("AIA171.Y", "G", "R")));
        expect("chunk count", p.chunks.size() == 2);

        float[][] back = pixels(p, w, h);
        expect("AIA171.Y round-trips through half", same(y, back[0]));
        expect("G round-trips through half", same(g, back[1]));
        expect("R round-trips through half", same(r, back[2]));

        // Names: the writer must refuse what a strict reader would.
        expect("32-byte name refused", throwsIae(() -> ExrWriter.checkName("a".repeat(32))));
        expect("non-ASCII name refused", throwsIae(() -> ExrWriter.checkName("café")));
        expect("31-byte name accepted", !throwsIae(() -> ExrWriter.checkName("a".repeat(31))));

        // The decoder that started all this.
        File sips = new File("/usr/bin/sips");
        if (sips.isFile()) {
            File png = File.createTempFile("exrcheck", ".png");
            png.deleteOnExit();
            Process proc = new ProcessBuilder(sips.getPath(), "-s", "format", "png", file.getPath(), "--out", png.getPath())
                    .redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();
            expect("macOS ImageIO decodes the file (sips exit " + code + (code == 0 ? "" : ": " + out.strip()) + ")", code == 0 && png.length() > 0);
        } else {
            System.out.println("skip: no /usr/bin/sips (not macOS)");
        }

        System.out.println(failures == 0 ? "ExrWriterCheck: all passed" : "ExrWriterCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private record Parsed(int magic, int version, List<String> attributes, List<String> channelNames, List<byte[]> chunks) {}

    private static Parsed parse(byte[] b) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt(), version = bb.getInt();
        List<String> attrs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int chunkCount = -1;
        while (b[bb.position()] != 0) {
            String name = cstring(bb), type = cstring(bb);
            int size = bb.getInt();
            int valueAt = bb.position();
            attrs.add(name);
            switch (name) {
                case "channels" -> {
                    while (b[bb.position()] != 0) {
                        names.add(cstring(bb));
                        int pixelType = bb.getInt();
                        if (pixelType != 1)
                            throw new IOException("not half: " + pixelType);
                        bb.position(bb.position() + 12);
                    }
                }
                case "compression" -> {
                    if (b[valueAt] != 3)
                        throw new IOException("not ZIP: " + b[valueAt]);
                }
                case "dataWindow" -> {
                    int yMin = bb.getInt(valueAt + 4), yMax = bb.getInt(valueAt + 12);
                    chunkCount = (yMax - yMin + 1 + 15) / 16;
                }
                default -> {}
            }
            if (!"chlist".equals(type) && !"string".equals(type) && !"float".equals(type) && !"int".equals(type)
                    && !"box2i".equals(type) && !"v2f".equals(type) && !"compression".equals(type)
                    && !"lineOrder".equals(type) && !"rational".equals(type))
                throw new IOException("unexpected attribute type " + type);
            bb.position(valueAt + size);
        }
        bb.get(); // header terminator
        long[] offsets = new long[chunkCount];
        for (int i = 0; i < chunkCount; i++)
            offsets[i] = bb.getLong();
        List<byte[]> chunks = new ArrayList<>();
        for (long off : offsets) {
            bb.position((int) off);
            int y = bb.getInt(), size = bb.getInt();
            byte[] body = new byte[size];
            bb.get(body);
            chunks.add(body);
        }
        return new Parsed(magic, version, attrs, names, chunks);
    }

    private static String cstring(ByteBuffer bb) {
        StringBuilder sb = new StringBuilder();
        byte c;
        while ((c = bb.get()) != 0)
            sb.append((char) c);
        return sb.toString();
    }

    // Per channel in file order, top row first.
    private static float[][] pixels(Parsed p, int w, int h) throws Exception {
        int nCh = p.channelNames.size();
        float[][] out = new float[nCh][w * h];
        int y = 0;
        for (byte[] body : p.chunks) {
            int lines = Math.min(16, h - y);
            int rawSize = lines * nCh * 2 * w;
            byte[] raw = body.length == rawSize ? body : unpredict(inflate(body, rawSize));
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for (int line = 0; line < lines; line++)
                for (int c = 0; c < nCh; c++)
                    for (int x = 0; x < w; x++)
                        out[c][(y + line) * w + x] = Float.float16ToFloat(bb.getShort());
            y += lines;
        }
        return out;
    }

    private static byte[] inflate(byte[] data, int size) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] out = new byte[size];
        int n = inflater.inflate(out);
        inflater.end();
        if (n != size)
            throw new IOException("inflated " + n + " of " + size);
        return out;
    }

    // The inverse of ExrWriter.predict: undo the delta, then un-interleave the two halves.
    private static byte[] unpredict(byte[] t) {
        int n = t.length;
        for (int i = 1; i < n; i++)
            t[i] = (byte) ((t[i] & 0xFF) + (t[i - 1] & 0xFF) - 128);
        byte[] out = new byte[n];
        int front = 0, back = (n + 1) / 2;
        for (int i = 0; i < n; ) {
            out[i++] = t[front++];
            if (i < n)
                out[i++] = t[back++];
        }
        return out;
    }

    private static boolean same(float[] expected, float[] actual) {
        for (int i = 0; i < expected.length; i++) {
            float viaHalf = Float.float16ToFloat(Float.floatToFloat16(expected[i]));
            if (viaHalf != actual[i]) {
                System.out.println("  mismatch at " + i + ": expected " + viaHalf + " got " + actual[i]);
                return false;
            }
        }
        return true;
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
