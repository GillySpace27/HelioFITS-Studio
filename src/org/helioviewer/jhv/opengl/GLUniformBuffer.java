package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;

final class GLUniformBuffer {

    private final int binding;
    private final int usage;
    private final int byteSize;
    private final FloatBuffer values;

    private GLBO buffer;
    private float[] uploadedValues;

    GLUniformBuffer(int floatCount, int _binding, int _usage) {
        binding = _binding;
        usage = _usage;
        byteSize = floatCount * Float.BYTES;
        values = BufferUtils.newFloatBuffer(floatCount);
    }

    void init() {
        if (buffer == null)
            buffer = new GLBO(GL.UNIFORM_BUFFER, usage);
    }

    void bindBlock(int programID, String name) {
        GLSLShader.setupUniformBlock(programID, name, binding, byteSize);
        bind();
    }

    FloatBuffer begin() {
        return values.clear();
    }

    FloatBuffer begin(FloatBuffer source) {
        return BufferUtils.putRemaining(values.clear(), source);
    }

    void upload() {
        prepare();
        buffer.setBufferData(values);
        uploadedValues = null;
    }

    void uploadIfChanged() {
        prepare();
        if (uploadedValues != null && valuesMatch())
            return;

        buffer.setBufferData(values);
        if (uploadedValues == null)
            uploadedValues = new float[values.capacity()];
        values.get(values.position(), uploadedValues);
    }

    private boolean valuesMatch() {
        int position = values.position();
        for (int i = 0; i < uploadedValues.length; i++) {
            if (Float.floatToRawIntBits(uploadedValues[i]) != Float.floatToRawIntBits(values.get(position + i)))
                return false;
        }
        return true;
    }

    private void prepare() {
        if (values.position() != values.capacity())
            throw new IllegalStateException("Uniform block contains " + values.position() + " of " + values.capacity() + " floats");
        values.flip();
    }

    void bind() {
        GL.glBindBufferBase(GL.UNIFORM_BUFFER, binding, buffer.getID());
    }

    void dispose() {
        if (buffer != null) {
            buffer.delete();
            buffer = null;
        }
        uploadedValues = null;
    }

}
