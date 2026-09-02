package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.display.MapMode;

public final class GLSLSolar {

    private static final VertexArrayObject quad = new VertexArrayObject(false, VertexAttribute.floats(0, 4, 0, 0));

    private static final FloatBuffer vertices = BufferUtils.newFloatBuffer(16).put(new float[]{-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1}).flip();

    static void init() {
        quad.init();
        quad.uploadVertexBuffer(vertices);
    }

    static void dispose() {
        quad.dispose();
    }

    private static void render() {
        quad.bind();
        GL.glDrawArrays(GL.TRIANGLE_STRIP, 0, 4);
    }

    static void renderSphere() {
        GLSLSolarShader.useSphere();
        render();
    }

    public static void renderImage(MapMode mode, float[] pv0, float[] pv1) {
        GLSLSolarShader.useImage(mode, pv0, pv1);
        render();
    }

    private GLSLSolar() {
    }
}
