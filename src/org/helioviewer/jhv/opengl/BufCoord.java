package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.math.Vec3;

public class BufCoord {

    private static final int FLOATS_PER_VERTEX = 6;

    private int count;
    private FloatBuffer buffer;

    public BufCoord(int capacity) {
        buffer = BufferUtils.newFloatBuffer(Math.multiplyExact(capacity, FLOATS_PER_VERTEX));
    }

    private void ensureCapacity() {
        if (buffer.remaining() >= FLOATS_PER_VERTEX)
            return;

        int newCapacity = Math.max(1, count * 2);
        FloatBuffer newBuffer = BufferUtils.newFloatBuffer(Math.multiplyExact(newCapacity, FLOATS_PER_VERTEX));
        buffer.flip();
        newBuffer.put(buffer);
        buffer = newBuffer;
    }

    public void putCoord(float x, float y, float z, float w, float c0, float c1) {
        ensureCapacity();
        buffer.put(x).put(y).put(z).put(w).put(c0).put(c1);

        count++;
    }

    public void putCoord(float x, float y, float z, float w, float[] c) {
        putCoord(x, y, z, w, c[0], c[1]);
    }

    public void putCoord(Vec3 v, float[] c) {
        putCoord((float) v.x, (float) v.y, (float) v.z, 1, c[0], c[1]);
    }

    public int getCount() {
        return count;
    }

    public void clear() {
        count = 0;
        buffer.clear();
    }

    public FloatBuffer toBuffer() { // Call clear() before appending again.
        return buffer.flip();
    }

}
