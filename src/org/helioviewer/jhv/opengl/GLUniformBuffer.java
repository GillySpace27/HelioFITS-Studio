package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;

final class GLUniformBuffer {

    private final int binding;
    private final int usage;
    private final int byteSize;
    private final FloatBuffer values;

    private GLBO buffer;

    GLUniformBuffer(int floatCount, int _binding, int _usage) {
        binding = _binding;
        usage = _usage;
        byteSize = Math.multiplyExact(floatCount, Float.BYTES);
        values = BufferUtils.newFloatBuffer(floatCount);
    }

    void init() {
        if (buffer == null)
            buffer = new GLBO(GL.UNIFORM_BUFFER, usage);
    }

    void bindBlock(int programID, String name) {
        GLSLShader.setupUBO(programID, name, buffer.getID(), binding);
    }

    FloatBuffer begin() {
        return values.clear();
    }

    void uploadIfChanged() {
        if (values.position() != values.capacity())
            throw new IllegalStateException("Uniform block contains " + values.position() + " of " + values.capacity() + " floats");
        values.flip();
        buffer.setBufferDataIfChanged(byteSize, values);
    }

    void dispose() {
        if (buffer == null)
            return;
        buffer.delete();
        buffer = null;
    }

}
