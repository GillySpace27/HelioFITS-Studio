package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;

public final class GLSLSolar {

    private static final VAO quad = new VAO(false, VertexAttribute.floats(0, 4, 0, 0));

    private static final FloatBuffer vertices = BufferUtils.newFloatBuffer(16).put(new float[]{-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1}).flip();

    static void init() {
        quad.init();
        quad.uploadVertexBuffer(vertices);
    }

    static void dispose() {
        quad.dispose();
    }

    public static void render() {
        quad.bind();
        GL.glDrawArrays(GL.TRIANGLE_STRIP, 0, 4);
    }

    static void renderSphere() {
        GLSLSolarShader.sphere.use();
        render();
    }

    private GLSLSolar() {
    }
}
