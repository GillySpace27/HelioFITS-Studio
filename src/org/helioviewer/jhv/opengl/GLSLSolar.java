package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;

public final class GLSLSolar {

    private static final String VERTEX = "/glsl/solarScreen.vert";

    private static final VertexArrayObject quad = new VertexArrayObject(false, VertexAttribute.floats(0, 4, 0, 0));
    private static final SphereShader sphere = new SphereShader();
    private static final UniformBufferObject screenBuffer = new UniformBufferObject(UniformBlockLayout.SOLAR_SCREEN, GL.STREAM_DRAW);

    private static final FloatBuffer vertices = BufferUtils.newFloatBuffer(16).put(new float[]{-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1}).flip();

    static void init() {
        screenBuffer.init();
        screenBuffer.bind();
        quad.init();
        quad.uploadVertexBuffer(vertices);
        sphere._init();
    }

    static void dispose() {
        sphere._dispose();
        quad.dispose();
        screenBuffer.dispose();
    }

    static void bindScreen(MapView mv, Viewport vp) {
        MapScale scale = mv.scale(vp);
        FloatBuffer values = screenBuffer.begin(Transform.getInverse());
        values.put((float) scale.toMapX(0)).put((float) scale.toMapX(1));
        values.put((float) scale.toMapY(0)).put((float) scale.toMapY(1));
        values.put((float) mv.latiLongitudeOrigin()).put((float) mv.latiLatitudeOrigin());
        values.put((float) (1 / vp.aspect));
        values.put((float) scale.warpLambda());
        screenBuffer.upload();
    }

    static void renderScreen() {
        quad.bind();
        GL.glDrawArrays(GL.TRIANGLE_STRIP, 0, 4);
    }

    static void renderSphere() {
        sphere.use();
        renderScreen();
    }

    private static final class SphereShader extends GLSLShader {
        private SphereShader() {
            super(VERTEX, "/glsl/solarSphere.frag");
        }

        @Override
        protected void initUniforms(int id) {
            setupUniformBlock(id, UniformBlockLayout.SOLAR_SCREEN);
        }
    }

    private GLSLSolar() {
    }
}
