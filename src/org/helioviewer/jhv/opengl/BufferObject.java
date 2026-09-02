package org.helioviewer.jhv.opengl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.helioviewer.jhv.base.BufferUtils;

class BufferObject {

    private static ByteBuffer uploadBuffer;

    private final int target;
    private final int usage;

    private int bufferID;

    BufferObject(int _target, int _usage) {
        target = _target;
        bufferID = GL.glGenBuffer();
        usage = _usage;
    }

    void delete() {
        if (bufferID == -1)
            return;
        GL.glDeleteBuffer(bufferID);
        bufferID = -1;
    }

    void bind() {
        GL.glBindBuffer(target, bufferID);
    }

    void setBufferData(ByteBuffer buffer) {
        ByteBuffer data = stage(buffer);
        setBufferData(data.remaining(), data);
    }

    void setBufferData(FloatBuffer buffer) {
        requireDirect(buffer);
        setBufferData(buffer.remaining() * Float.BYTES, buffer);
    }

    void setBufferData(IntBuffer buffer) {
        requireDirect(buffer);
        setBufferData(buffer.remaining() * Integer.BYTES, buffer);
    }

    void setBufferData(ShortBuffer buffer) {
        requireDirect(buffer);
        setBufferData(buffer.remaining() * Short.BYTES, buffer);
    }

    private void setBufferData(int size, Buffer buffer) {
        GL.glBindBuffer(target, bufferID);
        if (usage == GL.STATIC_DRAW) {
            switch (buffer) {
                case ByteBuffer data -> GL.glBufferData(target, data, usage);
                case FloatBuffer data -> GL.glBufferData(target, data, usage);
                case IntBuffer data -> GL.glBufferData(target, data, usage);
                case ShortBuffer data -> GL.glBufferData(target, data, usage);
                default -> throw new AssertionError();
            }
            return;
        }

        GL.glBufferData(target, size, usage); // orphan, https://www.khronos.org/opengl/wiki/Buffer_Object_Streaming#Buffer_re-specification
        switch (buffer) {
            case ByteBuffer data -> GL.glBufferSubData(target, 0, data);
            case FloatBuffer data -> GL.glBufferSubData(target, 0, data);
            case IntBuffer data -> GL.glBufferSubData(target, 0, data);
            case ShortBuffer data -> GL.glBufferSubData(target, 0, data);
            default -> throw new AssertionError();
        }
    }

    private static void requireDirect(Buffer buffer) {
        if (!buffer.isDirect())
            throw new IllegalArgumentException("Buffer must be direct");
    }

    private static ByteBuffer stage(ByteBuffer buffer) {
        if (buffer.isDirect())
            return buffer;

        int size = buffer.remaining();
        if (uploadBuffer == null || uploadBuffer.capacity() < size)
            uploadBuffer = BufferUtils.newByteBuffer(size);
        return BufferUtils.putRemaining(uploadBuffer.clear(), buffer).flip();
    }

    static void releaseUploadBuffer() {
        uploadBuffer = null;
    }

    int getID() {
        return bufferID;
    }

}
