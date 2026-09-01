package org.helioviewer.jhv.opengl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.helioviewer.jhv.base.BufferUtils;

class GLBO {

    private static ByteBuffer uploadBuffer;

    private final int target;
    private final int usage;

    private int bufferID;
    private float[] lastFloatData;

    GLBO(int _target, int _usage) {
        target = _target;
        bufferID = GL.glGenBuffer();
        usage = _usage;
    }

    void delete() {
        if (bufferID == -1)
            return;
        GL.glDeleteBuffer(bufferID);
        bufferID = -1;
        lastFloatData = null;
    }

    void bind() {
        GL.glBindBuffer(target, bufferID);
    }

    void setBufferData(Buffer buffer) {
        if (usage == GL.STATIC_DRAW) {
            GL.glBindBuffer(target, bufferID);
            switch (buffer) {
                case ByteBuffer byteBuffer -> GL.glBufferData(target, BufferUtils.directByteBuffer(byteBuffer), usage);
                case FloatBuffer floatBuffer -> GL.glBufferData(target, BufferUtils.directFloatBuffer(floatBuffer), usage);
                case IntBuffer intBuffer -> GL.glBufferData(target, BufferUtils.directIntBuffer(intBuffer), usage);
                case ShortBuffer shortBuffer -> GL.glBufferData(target, BufferUtils.directShortBuffer(shortBuffer), usage);
                default -> throw new IllegalArgumentException("Unsupported buffer type: " + buffer.getClass().getName());
            }
            return;
        }

        int size = switch (buffer) {
            case ByteBuffer byteBuffer -> byteBuffer.remaining();
            case FloatBuffer floatBuffer -> Math.multiplyExact(floatBuffer.remaining(), Float.BYTES);
            case IntBuffer intBuffer -> Math.multiplyExact(intBuffer.remaining(), Integer.BYTES);
            case ShortBuffer shortBuffer -> Math.multiplyExact(shortBuffer.remaining(), Short.BYTES);
            default -> throw new IllegalArgumentException("Unsupported buffer type: " + buffer.getClass().getName());
        };
        setBufferData(size, buffer);
    }

    void setBufferData(int size, Buffer buffer) {
        GL.glBindBuffer(target, bufferID);
        GL.glBufferData(target, size, usage); // orphan, https://www.khronos.org/opengl/wiki/Buffer_Object_Streaming#Buffer_re-specification
        switch (buffer) {
            case ByteBuffer byteBuffer -> GL.glBufferSubData(target, 0, stage(byteBuffer));
            case FloatBuffer floatBuffer -> GL.glBufferSubData(target, 0, BufferUtils.directFloatBuffer(floatBuffer));
            case IntBuffer intBuffer -> GL.glBufferSubData(target, 0, BufferUtils.directIntBuffer(intBuffer));
            case ShortBuffer shortBuffer -> GL.glBufferSubData(target, 0, BufferUtils.directShortBuffer(shortBuffer));
            default -> throw new IllegalArgumentException("Unsupported buffer type: " + buffer.getClass().getName());
        }
    }

    private static ByteBuffer stage(ByteBuffer buffer) {
        if (buffer.isDirect())
            return buffer;

        int size = buffer.remaining();
        if (uploadBuffer == null || uploadBuffer.capacity() < size)
            uploadBuffer = BufferUtils.newByteBuffer(size);
        uploadBuffer.clear().put(buffer.duplicate()).flip();
        return uploadBuffer;
    }

    static void releaseUploadBuffer() {
        uploadBuffer = null;
    }

    void setBufferDataIfChanged(int size, FloatBuffer buffer) {
        int count = buffer.remaining();
        if (lastFloatData != null && lastFloatData.length == count && floatDataMatches(buffer, count))
            return;

        setBufferData(size, buffer);

        if (lastFloatData == null || lastFloatData.length != count)
            lastFloatData = new float[count];
        buffer.get(0, lastFloatData);
    }

    private boolean floatDataMatches(FloatBuffer buffer, int count) {
        for (int i = 0; i < count; i++) {
            if (Float.floatToRawIntBits(lastFloatData[i]) != Float.floatToRawIntBits(buffer.get(i)))
                return false;
        }
        return true;
    }

    int getID() {
        return bufferID;
    }

}
