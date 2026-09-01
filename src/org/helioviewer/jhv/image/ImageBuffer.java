package org.helioviewer.jhv.image;

import java.lang.ref.Cleaner;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.function.DoubleUnaryOperator;

import javax.annotation.Nullable;

import org.lwjgl.system.MemoryUtil;

public final class ImageBuffer {

    // Set only by the direct-FITS decode path (FITSImage), which is the one place that still has
    // the physical (BZERO/BSCALE-corrected) pixel value before it gets stretched and squashed into
    // the [0,1] texture. Server-backed layers (JPX/JPIP movies) never carry this: the server bakes
    // in its own stretch before the client ever sees a pixel, so there is nothing to invert.
    public record PhysicalScale(float min, float max, DoubleUnaryOperator inverseStretch) {
        // displayFraction: the normalized [0,1] value that was fed into the LUT lookup, i.e. after
        // this buffer's own stretch but before any layer-level Levels/response adjustment -- the
        // caller is responsible for undoing those first.
        public double toPhysical(double displayFraction) {
            return min + inverseStretch.applyAsDouble(Math.clamp(displayFraction, 0, 1)) * (max - min);
        }
    }

    @Nullable
    private PhysicalScale physicalScale;

    public void setPhysicalScale(@Nullable PhysicalScale scale) {
        physicalScale = scale;
    }

    @Nullable
    public PhysicalScale physicalScale() {
        return physicalScale;
    }

    private static final Cleaner cleaner = Cleaner.create();

    public enum Format {
        Gray8(1), Gray16F(2), RGBA32(4);

        public final int bytes;

        Format(int _bytes) {
            bytes = _bytes;
        }
    }

    public final int width;
    public final int height;
    public final Format format;
    public final Buffer buffer;

    private final Cleaner.Cleanable cleanable;
    private volatile boolean explicitFreeProtected;
    private int measuredLevels = -1; // lazily counted once, then reused

    // Every 8th pixel of a 1024 square, which is far more than enough: the question is whether the
    // values sit on a 256-step lattice, and a sample cannot invent levels the data does not have.
    private static final int MAX_SAMPLES = 1 << 17;
    // The sample walks the image by this prime rather than by a constant stride. A stride divides
    // into the row width and then visits the same few columns in every row: a synthetic 4096-wide
    // ramp of 65536 distinct values reported 256 of them, indistinguishable from an 8-bit source.
    // A step coprime with the pixel count cannot lock onto row structure.
    private static final int SAMPLE_STEP = 104729;

    /**
     * How many distinct sample values this frame actually holds.
     *
     * <p>The container's depth cannot answer that. A JP2 browse product byte-scaled at ingest and
     * the calibrated FITS of the same instrument both arrive here as Gray16F, and only one of them
     * carries more than 256 levels. Counting the values present is the one statement about
     * quantization that does not take a header's word for it.
     *
     * <p>Sampled rather than exhaustive, so it is cheap enough to run on every displayed frame. A
     * sample can only ever undercount, which is the safe direction: a frame reported as having
     * more than 256 levels certainly has them.
     */
    public int measuredLevels() {
        if (measuredLevels < 0)
            measuredLevels = countLevels();
        return measuredLevels;
    }

    private int countLevels() {
        switch (format) {
            case Gray16F -> {
                ShortBuffer shorts = (ShortBuffer) buffer;
                int limit = Math.min(width * height, shorts.limit());
                boolean[] seen = new boolean[1 << 16]; // half-float bit patterns, so exact and small
                int distinct = 0;
                for (int i = 0, at = 0, n = sampleCount(limit); i < n; i++, at = nextSample(at, limit)) {
                    int bits = shorts.get(at) & 0xFFFF;
                    if (!seen[bits]) {
                        seen[bits] = true;
                        distinct++;
                    }
                }
                return distinct;
            }
            case Gray8 -> {
                ByteBuffer bytes = (ByteBuffer) buffer;
                int limit = Math.min(width * height, bytes.limit());
                boolean[] seen = new boolean[1 << 8];
                int distinct = 0;
                for (int i = 0, at = 0, n = sampleCount(limit); i < n; i++, at = nextSample(at, limit)) {
                    int v = bytes.get(at) & 0xFF;
                    if (!seen[v]) {
                        seen[v] = true;
                        distinct++;
                    }
                }
                return distinct;
            }
            // Colour is three interleaved channels of its own depth; one number would not mean anything.
            default -> {
                return 0;
            }
        }
    }

    private static int sampleCount(int limit) {
        return Math.max(0, Math.min(limit, MAX_SAMPLES));
    }

    private static int nextSample(int at, int limit) {
        // Coprime with limit except when limit is a multiple of the prime, where the walk would
        // revisit one index forever; a contiguous run is the honest fallback there.
        int step = limit % SAMPLE_STEP == 0 ? 1 : SAMPLE_STEP % limit;
        return (at + step) % limit;
    }

    public static ImageBuffer fromBytes(int width, int height, Format format, byte[] data) {
        return fromBytes(width, height, format, data, ImageFilter.NONE);
    }

    public static ImageBuffer fromBytes(int width, int height, Format format, byte[] data, ImageFilter filter) {
        if (format == Format.Gray16F)
            throw new IllegalArgumentException("Gray16F image buffers must be created from half-float data");
        if (!shouldFilter(format, filter))
            return new ImageBuffer(width, height, format, allocateFrom(data));
        return new ImageBuffer(width, height, Format.Gray16F, allocateFrom(filter.apply(data, width, height)));
    }

    public static ImageBuffer fromShorts(int width, int height, Format format, short[] data, ImageFilter filter) {
        if (format != Format.Gray16F)
            throw new IllegalArgumentException("Only Gray16F image buffers can be created from half-float data");
        short[] out = shouldFilter(format, filter) ? filter.apply(data, width, height) : data;
        return new ImageBuffer(width, height, format, allocateFrom(out));
    }

    public static WriteBuffer createWriteBuffer(int width, int height, Format format, ImageFilter filter) {
        return new WriteBuffer(width, height, format, filter);
    }

    private ImageBuffer(int _width, int _height, Format _format, ByteBuffer _buffer) {
        this(_width, _height, _format, _buffer, MemoryUtil.memAddress(_buffer));
    }

    private ImageBuffer(int _width, int _height, Format _format, ShortBuffer _buffer) {
        this(_width, _height, _format, _buffer, MemoryUtil.memAddress(_buffer));
    }

    private ImageBuffer(int _width, int _height, Format _format, Buffer _buffer, long address) {
        width = _width;
        height = _height;
        format = _format;
        buffer = _buffer;
        cleanable = cleaner.register(buffer, new BufferState(address));
    }

    public int byteSize() {
        return byteSize(width, height, format);
    }

    public void protectFromExplicitFree() {
        explicitFreeProtected = true;
    }

    public void allowExplicitFree() {
        explicitFreeProtected = false;
    }

    boolean free() {
        if (explicitFreeProtected)
            return false;
        cleanable.clean();
        return true;
    }

    public static final class WriteBuffer {
        private final int width;
        private final int height;
        private final Format inputFormat;
        private final ImageFilter filter;
        private final ImageBuffer directBuffer;
        private final byte[] byteArray;
        private final short[] shortArray;
        private final Buffer writeBuffer;

        private WriteBuffer(int _width, int _height, Format _format, ImageFilter _filter) {
            width = _width;
            height = _height;
            inputFormat = _format;
            filter = _filter;

            if (!ImageBuffer.shouldFilter(inputFormat, filter)) {
                directBuffer = allocate(width, height, inputFormat);
                byteArray = null;
                shortArray = null;
                writeBuffer = directBuffer.buffer;
            } else if (inputFormat == Format.Gray16F) {
                directBuffer = null;
                byteArray = null;
                shortArray = new short[width * height];
                writeBuffer = ShortBuffer.wrap(shortArray);
            } else {
                directBuffer = null;
                byteArray = new byte[byteSize(width, height, inputFormat)];
                shortArray = null;
                writeBuffer = ByteBuffer.wrap(byteArray);
            }
        }

        public ByteBuffer byteBuffer() {
            return (ByteBuffer) writeBuffer;
        }

        public ShortBuffer shortBuffer() {
            return (ShortBuffer) writeBuffer;
        }

        public ImageBuffer finish() {
            if (directBuffer != null)
                return directBuffer;
            return shortArray != null
                    ? fromShorts(width, height, inputFormat, shortArray, filter)
                    : fromBytes(width, height, inputFormat, byteArray, filter);
        }

    }

    private static boolean shouldFilter(Format format, ImageFilter filter) {
        return format != Format.RGBA32 && !filter.isNone();
    }

    private static final class BufferState implements Runnable {
        private long address;

        private BufferState(long _address) {
            address = _address;
        }

        @Override
        public void run() {
            if (address == 0)
                return;
            MemoryUtil.nmemFree(address);
            address = 0;
        }
    }

    private static ImageBuffer allocate(int width, int height, Format format) {
        int byteSize = byteSize(width, height, format);
        return switch (format) {
            case Gray8, RGBA32 -> new ImageBuffer(width, height, format, MemoryUtil.memAlloc(byteSize));
            case Gray16F -> new ImageBuffer(width, height, format, MemoryUtil.memAllocShort(byteSize / Short.BYTES));
        };
    }

    private static ByteBuffer allocateFrom(byte[] data) {
        ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
        buffer.put(data);
        return buffer.flip();
    }

    private static ShortBuffer allocateFrom(short[] data) {
        ShortBuffer buffer = MemoryUtil.memAllocShort(data.length);
        buffer.put(data);
        return buffer.flip();
    }

    private static int byteSize(int width, int height, Format format) {
        return width * height * format.bytes;
    }

}
