package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.opengl.model.ModelMaterial;

final class GLSLMeshMaterial {

    private static final int MATERIAL_FLOATS = 8;

    // Keep one immutable buffer per material so changing draw order only changes the binding.
    private final ModelMaterial data;
    private GLBO buffer;

    GLSLMeshMaterial(ModelMaterial _data) {
        data = _data;
    }

    void init() {
        if (buffer != null)
            return;

        FloatBuffer values = BufferUtils.newFloatBuffer(MATERIAL_FLOATS);
        values.put(data.red()).put(data.green()).put(data.blue()).put(data.alpha());
        values.put(data.alphaCutoff()).put(alphaMode(data.alphaMode()));
        values.put(data.baseColorTexture() == ModelMaterial.NO_TEXTURE ? 0 : 1).put(data.unlit() ? 1 : 0).flip();

        GLBO newBuffer = new GLBO(GL.UNIFORM_BUFFER, GL.STATIC_DRAW);
        try {
            newBuffer.setBufferData(values);
            buffer = newBuffer;
        } catch (RuntimeException | Error e) {
            newBuffer.delete();
            throw e;
        }
    }

    void bind() {
        GLSLMeshShader.bindMaterial(buffer.getID());
    }

    void dispose() {
        if (buffer == null)
            return;
        buffer.delete();
        buffer = null;
    }

    private static int alphaMode(ModelMaterial.AlphaMode mode) {
        return switch (mode) {
            case OPAQUE -> 0;
            case MASK -> 1;
            case BLEND -> 2;
        };
    }

}
