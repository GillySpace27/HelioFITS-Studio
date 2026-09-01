package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.opengl.model.ModelMaterial;

final class GLSLMeshMaterial {

    private static final int MATERIAL_FLOATS = 8;

    // Keep one immutable buffer per material so changing draw order only changes the binding.
    private final ModelMaterial data;
    private GLUniformBuffer buffer;

    GLSLMeshMaterial(ModelMaterial _data) {
        data = _data;
    }

    void init() {
        if (buffer != null)
            return;

        GLUniformBuffer newBuffer = new GLUniformBuffer(MATERIAL_FLOATS, GLSLShader.UBO.MESH_MATERIAL, GL.STATIC_DRAW);
        newBuffer.init();
        try {
            FloatBuffer values = newBuffer.begin();
            values.put(data.red()).put(data.green()).put(data.blue()).put(data.alpha());
            values.put(data.alphaCutoff()).put(alphaMode(data.alphaMode()));
            values.put(data.baseColorTexture() == ModelMaterial.NO_TEXTURE ? 0 : 1).put(data.unlit() ? 1 : 0);
            newBuffer.upload();
            buffer = newBuffer;
        } catch (RuntimeException | Error e) {
            newBuffer.dispose();
            throw e;
        }
    }

    void bind() {
        buffer.bind();
    }

    void dispose() {
        if (buffer == null)
            return;
        buffer.dispose();
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
