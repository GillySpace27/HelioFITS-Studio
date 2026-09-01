package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;

import org.helioviewer.jhv.base.BufferUtils;

public final class DirectBufVertex {

    private final ByteBuffer buffer;
    private final int count;

    public DirectBufVertex(BufVertex vertices) {
        buffer = copy(vertices.toBuffer());
        count = vertices.getCount();
    }

    private static ByteBuffer copy(ByteBuffer buffer) {
        ByteBuffer ret = BufferUtils.newByteBuffer(buffer.remaining());
        return BufferUtils.putRemaining(ret, buffer).flip();
    }

    ByteBuffer buffer() {
        return buffer;
    }

    int count() {
        return count;
    }

}
