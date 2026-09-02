package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;

abstract class GLSLScreenShader extends GLSLShader {

    private static final VertexArrayObject quad = new VertexArrayObject(false, VertexAttribute.floats(0, 4, 0, 0));
    private static final UniformBufferObject screenBuffer = new UniformBufferObject(UniformBlockLayout.SCREEN, GL.STREAM_DRAW);

    private static final FloatBuffer vertices = BufferUtils.newFloatBuffer(16).put(new float[]{-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1}).flip();

    GLSLScreenShader(String... _fragments) {
        super("/glsl/screen.vert", _fragments);
    }

    static void init() {
        screenBuffer.init();
        screenBuffer.bind();
        quad.init();
        quad.uploadVertexBuffer(vertices);
    }

    static void dispose() {
        quad.dispose();
        screenBuffer.dispose();
    }

    @Override
    protected void initUniforms(int id) {
        setupUniformBlock(id, UniformBlockLayout.SCREEN);
    }

    static void setView(MapView mv, Viewport vp) {
        MapScale scale = mv.scale(vp);
        FloatBuffer values = screenBuffer.begin(Transform.getInverse());
        values.put((float) scale.toMapX(0)).put((float) scale.toMapX(1));
        values.put((float) scale.toMapY(0)).put((float) scale.toMapY(1));
        values.put((float) mv.latiLongitudeOrigin()).put((float) mv.latiLatitudeOrigin());
        values.put((float) (1 / vp.aspect));
        values.put((float) scale.warpLambda());
        screenBuffer.upload();
    }

    final void draw() {
        quad.bind();
        GL.glDrawArrays(GL.TRIANGLE_STRIP, 0, 4);
    }

}
