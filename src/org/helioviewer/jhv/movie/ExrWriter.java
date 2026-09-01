package org.helioviewer.jhv.movie;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;

/**
 * A minimal OpenEXR writer: one part, scanlines, half-float channels, ZIP compression, and
 * string, float, int and rational attributes. That is all a layered export needs and about a
 * fifth of what the format offers. It exists because ffmpeg's encoder writes one RGB image with
 * no layers and no metadata, uncompressed 32-bit float, and leaves out the required
 * pixelAspectRatio attribute, which is why macOS refused to open the files.
 *
 * <p>The layout is the OpenEXR 2 single-part scanline file: magic, version, a header of typed
 * attributes ending in a null byte, a table of absolute chunk offsets, then the chunks. A chunk
 * is 16 scanlines, each holding every channel's row in channel-list order, run through the
 * format's byte reorder and delta predictor and then deflated; a chunk that does not shrink is
 * stored raw, which a reader detects from its size.
 *
 * <p>Names are kept to 31 bytes so the long-names flag is never needed: a reader that does not
 * know it would refuse the file. Channels are written sorted, which is how every file written
 * by the OpenEXR library orders them.
 *
 * <p>Verified by extra/test/ExrWriterCheck.java, which reads its own output back and, on macOS,
 * asks the system decoder to.
 */
final class ExrWriter {

    static final int MAX_NAME = 31;
    private static final int MAGIC = 20000630;
    private static final int VERSION = 2;
    private static final int LINES_PER_CHUNK = 16; // ZIP_COMPRESSION
    private static final int PIXEL_TYPE_HALF = 1;
    private static final byte COMPRESSION_ZIP = 3;
    private static final byte LINE_ORDER_INCREASING_Y = 0;

    final int width;
    final int height;
    private final Map<String, float[]> channels = new TreeMap<>(); // sorted: strcmp order, for ASCII names
    private final ByteArrayOutputStream attributes = new ByteArrayOutputStream();

    ExrWriter(int _width, int _height) {
        if (_width <= 0 || _height <= 0)
            throw new IllegalArgumentException("empty image");
        width = _width;
        height = _height;
    }

    /** A channel of width*height values, top row first. Stored as half float: 11 significant bits. */
    void channel(String name, float[] values) {
        if (values.length != width * height)
            throw new IllegalArgumentException(name + ": " + values.length + " values for " + width + 'x' + height);
        channels.put(checkName(name), values);
    }

    void attribute(String name, String value) {
        attribute(attributes, name, "string", value.getBytes(StandardCharsets.UTF_8));
    }

    void attribute(String name, float value) {
        attribute(attributes, name, "float", le(4).putFloat(value).array());
    }

    void attribute(String name, int value) {
        attribute(attributes, name, "int", le(4).putInt(value).array());
    }

    void rational(String name, int numerator, int denominator) {
        attribute(attributes, name, "rational", le(8).putInt(numerator).putInt(denominator).array());
    }

    private static void attribute(ByteArrayOutputStream out, String name, String type, byte[] value) {
        out.writeBytes(checkName(name).getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        out.writeBytes(le(4).putInt(value.length).array());
        out.writeBytes(value);
    }

    static String checkName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME)
            throw new IllegalArgumentException("EXR name must be 1 to " + MAX_NAME + " bytes: " + name);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == 0 || c > 127)
                throw new IllegalArgumentException("EXR name must be ASCII: " + name);
        }
        return name;
    }

    void write(File file) throws IOException {
        if (channels.isEmpty())
            throw new IOException("EXR with no channels");

        int chunkCount = (height + LINES_PER_CHUNK - 1) / LINES_PER_CHUNK;
        List<byte[]> chunks = new ArrayList<>(chunkCount);
        Deflater deflater = new Deflater();
        try {
            for (int c = 0; c < chunkCount; c++)
                chunks.add(chunk(c * LINES_PER_CHUNK, deflater));
        } finally {
            deflater.end();
        }

        byte[] header = header();
        long offset = header.length + 8L * chunkCount;
        ByteBuffer table = le(8 * chunkCount);
        for (byte[] chunk : chunks) {
            table.putLong(offset);
            offset += chunk.length;
        }

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            out.write(header);
            out.write(table.array());
            for (byte[] chunk : chunks)
                out.write(chunk);
        }
    }

    private byte[] header() {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.writeBytes(le(8).putInt(MAGIC).putInt(VERSION).array());

        ByteArrayOutputStream chlist = new ByteArrayOutputStream();
        for (String name : channels.keySet()) {
            chlist.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
            chlist.write(0);
            chlist.writeBytes(le(16).putInt(PIXEL_TYPE_HALF).put((byte) 0).put(new byte[3]).putInt(1).putInt(1).array()); // type, pLinear, reserved, x/y sampling
        }
        chlist.write(0);

        // The eight the format requires, in the order the OpenEXR library writes them.
        byte[] window = le(16).putInt(0).putInt(0).putInt(width - 1).putInt(height - 1).array();
        attribute(h, "channels", "chlist", chlist.toByteArray());
        attribute(h, "compression", "compression", new byte[]{COMPRESSION_ZIP});
        attribute(h, "dataWindow", "box2i", window);
        attribute(h, "displayWindow", "box2i", window);
        attribute(h, "lineOrder", "lineOrder", new byte[]{LINE_ORDER_INCREASING_Y});
        attribute(h, "pixelAspectRatio", "float", le(4).putFloat(1).array());
        attribute(h, "screenWindowCenter", "v2f", le(8).putFloat(0).putFloat(0).array());
        attribute(h, "screenWindowWidth", "float", le(4).putFloat(1).array());
        h.writeBytes(attributes.toByteArray());
        h.write(0);
        return h.toByteArray();
    }

    private byte[] chunk(int y0, Deflater deflater) {
        int lines = Math.min(LINES_PER_CHUNK, height - y0);
        byte[] raw = new byte[lines * channels.size() * 2 * width];
        int p = 0;
        for (int y = y0; y < y0 + lines; y++) {
            int base = y * width;
            for (float[] values : channels.values()) {
                for (int x = 0; x < width; x++) {
                    short half = Float.floatToFloat16(values[base + x]);
                    raw[p++] = (byte) half;
                    raw[p++] = (byte) (half >>> 8);
                }
            }
        }

        byte[] packed = deflate(predict(raw), deflater);
        byte[] body = packed.length < raw.length ? packed : raw; // a chunk that did not shrink is stored raw
        ByteBuffer out = le(8 + body.length).putInt(y0).putInt(body.length);
        out.put(body);
        return out.array();
    }

    /**
     * The format's two pre-filters, applied before deflate so pixel bytes compress like the
     * smooth signal they are: the bytes are split into two runs (every other byte to the front
     * half, the rest to the back), then each byte becomes its difference from the previous one,
     * offset by 128. Straight from ImfZip.cpp; the reader undoes them in the opposite order.
     */
    static byte[] predict(byte[] raw) {
        int n = raw.length;
        byte[] t = new byte[n];
        int front = 0, back = (n + 1) / 2;
        for (int i = 0; i < n; ) {
            t[front++] = raw[i++];
            if (i < n)
                t[back++] = raw[i++];
        }
        int prev = t[0] & 0xFF;
        for (int i = 1; i < n; i++) {
            int cur = t[i] & 0xFF;
            t[i] = (byte) (cur - prev + 128);
            prev = cur;
        }
        return t;
    }

    private static byte[] deflate(byte[] data, Deflater deflater) {
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2);
        byte[] buf = new byte[1 << 14];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static ByteBuffer le(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

}
