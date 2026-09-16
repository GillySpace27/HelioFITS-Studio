package org.helioviewer.jhv.opengl;

final class VertexAttribute {

    private final int index;
    private final int components;
    private final int type;
    private final boolean normalized;
    private final int stride;
    private final long offset;
    private final int divisor;

    private VertexAttribute(int _index, int _components, int _type, boolean _normalized, int _stride, long _offset, int _divisor) {
        index = _index;
        components = _components;
        type = _type;
        normalized = _normalized;
        stride = _stride;
        offset = _offset;
        divisor = _divisor;
    }

    static VertexAttribute floats(int index, int components, int stride, long offset) {
        return new VertexAttribute(index, components, GL.FLOAT, false, stride, offset, 0);
    }

    static VertexAttribute instancedFloats(int index, int components, int stride, long offset) {
        return new VertexAttribute(index, components, GL.FLOAT, false, stride, offset, 1);
    }

    static VertexAttribute normalizedUnsignedBytes(int index, int components, int stride, long offset) {
        return new VertexAttribute(index, components, GL.UNSIGNED_BYTE, true, stride, offset, 0);
    }

    static VertexAttribute instancedNormalizedUnsignedBytes(int index, int components, int stride, long offset) {
        return new VertexAttribute(index, components, GL.UNSIGNED_BYTE, true, stride, offset, 1);
    }

    void enable() {
        GL.glEnableVertexAttribArray(index);
        GL.glVertexAttribPointer(index, components, type, normalized, stride, offset);
        if (divisor != 0)
            GL.glVertexAttribDivisor(index, divisor);
    }

}
