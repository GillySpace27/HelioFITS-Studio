package org.helioviewer.jhv.base;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public class BufferUtils {

    public static ByteBuffer newByteBuffer(int len) {
        return ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder());
    }

    public static ShortBuffer newShortBuffer(int len) {
        return newByteBuffer(Math.multiplyExact(Short.BYTES, len)).asShortBuffer();
    }

    public static IntBuffer newIntBuffer(int len) {
        return newByteBuffer(Math.multiplyExact(Integer.BYTES, len)).asIntBuffer();
    }

    public static FloatBuffer newFloatBuffer(int len) {
        return newByteBuffer(Math.multiplyExact(Float.BYTES, len)).asFloatBuffer();
    }

    public static ByteBuffer putRemaining(ByteBuffer destination, ByteBuffer source) {
        return putRange(destination, source, source.position(), source.remaining());
    }

    public static ByteBuffer putRange(ByteBuffer destination, ByteBuffer source, int sourcePosition, int count) {
        int position = destination.position();
        return destination.put(position, source, sourcePosition, count).position(position + count);
    }

    public static ShortBuffer putRemaining(ShortBuffer destination, ShortBuffer source) {
        int position = destination.position();
        return destination.put(position, source, source.position(), source.remaining())
                .position(position + source.remaining());
    }

    public static IntBuffer putRemaining(IntBuffer destination, IntBuffer source) {
        int position = destination.position();
        return destination.put(position, source, source.position(), source.remaining())
                .position(position + source.remaining());
    }

    public static FloatBuffer putRemaining(FloatBuffer destination, FloatBuffer source) {
        int position = destination.position();
        return destination.put(position, source, source.position(), source.remaining())
                .position(position + source.remaining());
    }

    public static ByteBuffer directByteBuffer(ByteBuffer buffer) {
        if (buffer.isDirect())
            return buffer;

        ByteBuffer copy = newByteBuffer(buffer.remaining());
        return putRemaining(copy, buffer).flip();
    }

    public static ShortBuffer directShortBuffer(ShortBuffer buffer) {
        if (buffer.isDirect())
            return buffer;

        ShortBuffer copy = newShortBuffer(buffer.remaining());
        return putRemaining(copy, buffer).flip();
    }

    public static IntBuffer directIntBuffer(IntBuffer buffer) {
        if (buffer.isDirect())
            return buffer;

        IntBuffer copy = newIntBuffer(buffer.remaining());
        return putRemaining(copy, buffer).flip();
    }

    public static FloatBuffer directFloatBuffer(FloatBuffer buffer) {
        if (buffer.isDirect())
            return buffer;

        FloatBuffer copy = newFloatBuffer(buffer.remaining());
        return putRemaining(copy, buffer).flip();
    }

}
