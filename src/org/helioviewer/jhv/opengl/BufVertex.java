package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

import org.helioviewer.jhv.math.Vec3;

public class BufVertex {

    private static final int VERTEX_BYTES = 4 * Float.BYTES;
    private static final int COLOR_BYTES = 4;
    private static final int MIN_CAPACITY = 64;

    private final byte[] byteLast = new byte[VERTEX_BYTES];
    private final FloatBuffer bufferLast = ByteBuffer.wrap(byteLast).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private int count;

    private ByteBuffer vertxBuffer;
    private byte[] arrayVertx;

    private ByteBuffer colorBuffer;
    private byte[] arrayColor;

    public BufVertex() {
        this(0);
    }

    public BufVertex(int capacity) {
        arrayVertx = new byte[Math.multiplyExact(capacity, VERTEX_BYTES)];
        vertxBuffer = ByteBuffer.wrap(arrayVertx);
        arrayColor = new byte[Math.multiplyExact(capacity, COLOR_BYTES)];
        colorBuffer = ByteBuffer.wrap(arrayColor);
    }

    private void ensureCapacity() {
        int capacity = arrayColor.length / COLOR_BYTES;
        if (count < capacity)
            return;

        int newCapacity = Math.max(MIN_CAPACITY, Math.multiplyExact(capacity, 2));
        arrayVertx = Arrays.copyOf(arrayVertx, Math.multiplyExact(newCapacity, VERTEX_BYTES));
        vertxBuffer = ByteBuffer.wrap(arrayVertx);
        arrayColor = Arrays.copyOf(arrayColor, Math.multiplyExact(newCapacity, COLOR_BYTES));
        colorBuffer = ByteBuffer.wrap(arrayColor);
    }

    public void putVertex(Vec3 v, byte[] color) {
        putVertex((float) v.x, (float) v.y, (float) v.z, 1, color);
    }

    public void putVertex(float x, float y, float z, float w, byte[] color) {
        bufferLast.put(0, x).put(1, y).put(2, z).put(3, w);
        repeatVertex(color);
    }

    public void repeatVertex(byte[] color) {
        ensureCapacity();
        System.arraycopy(byteLast, 0, arrayVertx, count * VERTEX_BYTES, VERTEX_BYTES);
        System.arraycopy(color, 0, arrayColor, count * COLOR_BYTES, COLOR_BYTES);

        count++;
    }

    public void putQuad2D(float left, float bottom, float right, float top, byte[] color) {
        putVertex(left, bottom, 0, 1, color);
        putVertex(right, bottom, 0, 1, color);
        putVertex(right, top, 0, 1, color);
        putVertex(left, bottom, 0, 1, color);
        putVertex(right, top, 0, 1, color);
        putVertex(left, top, 0, 1, color);
    }

    public void putQuad2DStrip(float left, float bottom, float right, float top, byte[] color) {
        putVertex(left, bottom, 0, 1, color);
        putVertex(right, bottom, 0, 1, color);
        putVertex(left, top, 0, 1, color);
        putVertex(right, top, 0, 1, color);
    }

    public int getCount() {
        return count;
    }

    int vertexByteLength() {
        return count * VERTEX_BYTES;
    }

    int colorByteLength() {
        return count * COLOR_BYTES;
    }

    public void clear() {
        count = 0;
    }

    public ByteBuffer toVertexBuffer() {
        return vertxBuffer.limit(vertexByteLength());
    }

    public ByteBuffer toColorBuffer() {
        return colorBuffer.limit(colorByteLength());
    }

    public static BufVertex join(List<BufVertex> list) {
        int listSize = list.size();
        if (listSize == 0)
            throw new IllegalArgumentException("Empty BufVertex list");
        if (listSize == 1)
            return list.getFirst();

        int retCount = 0;
        for (BufVertex b : list) {
            retCount = Math.addExact(retCount, b.count);
        }
        BufVertex ret = new BufVertex(retCount);

        int vertexOffset = 0;
        int colorOffset = 0;
        for (BufVertex b : list) {
            int vertexBytes = b.vertexByteLength();
            System.arraycopy(b.arrayVertx, 0, ret.arrayVertx, vertexOffset, vertexBytes);
            vertexOffset += vertexBytes;

            int colorBytes = b.colorByteLength();
            System.arraycopy(b.arrayColor, 0, ret.arrayColor, colorOffset, colorBytes);
            colorOffset += colorBytes;
        }
        ret.count = retCount;

        return ret;
    }

}
