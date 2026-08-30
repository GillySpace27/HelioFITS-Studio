package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;

final class GLSLMeshShader extends GLSLShader {

    static final GLSLMeshShader mesh = new GLSLMeshShader();

    private static final int FRAME_FLOATS = 20;
    private static final int FRAME_SIZE = FRAME_FLOATS * Float.BYTES;
    private static final FloatBuffer frameBuf = BufferUtils.newFloatBuffer(FRAME_FLOATS);

    private static GLBO frameBO;

    private GLSLMeshShader() {
        super("/glsl/mesh.vert", "/glsl/mesh.frag");
    }

    static void init() {
        frameBO = new GLBO(GL.UNIFORM_BUFFER, GL.STREAM_DRAW);
        mesh._init(false);
    }

    static void dispose() {
        mesh._dispose();
        frameBO.delete();
    }

    @Override
    protected void initUniforms(int id) {
        setupUBO(id, "FrameBlock", frameBO.getID(), UBO.MESH_FRAME);
        setupUBO(id, "MaterialBlock", 0, UBO.MESH_MATERIAL);
        setTextureUnit(id, "baseColorTexture", GLTexture.Unit.THREE);
    }

    static void bindFrame(FloatBuffer worldToClip, float lightX, float lightY, float lightZ) {
        frameBuf.clear().put(worldToClip.duplicate());
        frameBuf.put(lightX).put(lightY).put(lightZ).put(0).flip();
        frameBO.setBufferDataIfChanged(FRAME_SIZE, frameBuf);
    }

    static void bindMaterial(int bufferID) {
        GL.glBindBufferBase(GL.UNIFORM_BUFFER, UBO.MESH_MATERIAL, bufferID);
    }

}
