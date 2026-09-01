package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

final class GLSLMeshShader extends GLSLShader {

    static final GLSLMeshShader mesh = new GLSLMeshShader();

    private static final int FRAME_FLOATS = 20;
    static final int MATERIAL_FLOATS = 8;
    private static final GLUniformBuffer frameBuffer = new GLUniformBuffer(FRAME_FLOATS, UBO.MESH_FRAME, GL.STREAM_DRAW);

    private GLSLMeshShader() {
        super("/glsl/mesh.vert", "/glsl/mesh.frag");
    }

    static void init() {
        frameBuffer.init();
        try {
            mesh._init();
        } catch (RuntimeException | Error e) {
            frameBuffer.dispose();
            throw e;
        }
    }

    static void dispose() {
        mesh._dispose();
        frameBuffer.dispose();
    }

    @Override
    protected void initUniforms(int id) {
        frameBuffer.bindBlock(id, "FrameBlock");
        setupUniformBlock(id, "MaterialBlock", UBO.MESH_MATERIAL, MATERIAL_FLOATS * Float.BYTES);
        setTextureUnit(id, "baseColorTexture", GLTexture.Unit.THREE);
    }

    static void bindFrame(FloatBuffer worldToClip, float lightX, float lightY, float lightZ) {
        FloatBuffer values = frameBuffer.begin(worldToClip);
        values.put(lightX).put(lightY).put(lightZ).put(0);
        frameBuffer.uploadIfChanged();
    }

}
