package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

final class GLSLMeshShader extends GLSLShader {

    static final GLSLMeshShader mesh = new GLSLMeshShader();

    private int modelViewProjectionMatrixRef;
    private int normalMatrixRef;

    private GLSLMeshShader() {
        super("/glsl/mesh.vert", "/glsl/mesh.frag");
    }

    static void init() {
        mesh._init(false);
    }

    static void dispose() {
        mesh._dispose();
    }

    @Override
    protected void initUniforms(int id) {
        modelViewProjectionMatrixRef = GL.glGetUniformLocation(id, "modelViewProjectionMatrix");
        normalMatrixRef = GL.glGetUniformLocation(id, "normalMatrix");
        setupUBO(id, "MaterialBlock", 0, UBO.MESH_MATERIAL);
        setTextureUnit(id, "baseColorTexture", GLTexture.Unit.THREE);
    }

    void bindMatrices(FloatBuffer modelViewProjectionMatrix, FloatBuffer normalMatrix) {
        GL.glUniformMatrix4fv(modelViewProjectionMatrixRef, false, modelViewProjectionMatrix);
        GL.glUniformMatrix3fv(normalMatrixRef, false, normalMatrix);
    }

    static void bindMaterial(int bufferID) {
        GL.glBindBufferBase(GL.UNIFORM_BUFFER, UBO.MESH_MATERIAL, bufferID);
    }

}
