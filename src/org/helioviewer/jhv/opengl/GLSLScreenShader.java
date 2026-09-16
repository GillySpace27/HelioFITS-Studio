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

    // For the one image program that is drawn on geometry rather than on the shared quad: it still
    // reads the screen block (its vertex stage needs the radial scale), so it belongs here, but it
    // supplies its own vertex stage and its own draw call. See GLSLImageShader.warpSurface.
    GLSLScreenShader(String _vertex, String[] _fragments) {
        super(_vertex, _fragments);
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
        // The limb's share of the radial axis, which only the Box-Cox scale sets; zero tells the
        // shader to fall back on the geometric 1 / outerRadius. Three std140 rounding floats follow.
        values.put((float) scale.warpLimb()).put(0).put(0).put(0);
        screenBuffer.upload();
    }

    final void draw() {
        quad.bind();
        GL.glDrawArrays(GL.TRIANGLE_STRIP, 0, 4);
    }

}
