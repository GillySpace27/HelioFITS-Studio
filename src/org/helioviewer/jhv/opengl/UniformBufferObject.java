package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;

final class UniformBufferObject {

    private final UniformBlockLayout block;
    private final int usage;
    private final FloatBuffer values;

    private BufferObject buffer;
    private float[] uploadedValues;

    UniformBufferObject(UniformBlockLayout _block, int _usage) {
        block = _block;
        usage = _usage;
        values = BufferUtils.newFloatBuffer(block.floatCount);
    }

    void init() {
        if (buffer == null)
            buffer = new BufferObject(GL.UNIFORM_BUFFER, usage);
    }

    void bindBlock(int programID) {
        GLSLShader.setupUniformBlock(programID, block);
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
        GL.glBindBufferBase(GL.UNIFORM_BUFFER, block.binding, buffer.getID());
    }

    void dispose() {
        if (buffer != null) {
            buffer.delete();
            buffer = null;
        }
        uploadedValues = null;
    }

}
