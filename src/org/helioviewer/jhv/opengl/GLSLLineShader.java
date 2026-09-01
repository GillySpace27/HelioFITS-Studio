package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.display.Viewport;

class GLSLLineShader extends GLSLShader {

    static final GLSLLineShader line = new GLSLLineShader("/glsl/line.vert", "/glsl/line.frag");

    private static final int SCREEN_FLOATS = 24;
    private static final GLUniformBuffer screenBuffer = new GLUniformBuffer(SCREEN_FLOATS, UBO.LINE_SCREEN, GL.STREAM_DRAW);

    private int opaquePassRef;

    private GLSLLineShader(String vertex, String fragment) {
        super(vertex, fragment);
    }

    public static void init() {
        screenBuffer.init();
        try {
            line._init(false);
        } catch (RuntimeException | Error e) {
            screenBuffer.dispose();
            throw e;
        }
    }

    public static void dispose() {
        line._dispose();
        screenBuffer.dispose();
    }

    @Override
    protected void initUniforms(int id) {
        screenBuffer.bindBlock(id, "ScreenBlock");
        opaquePassRef = requiredUniform(id, "opaquePass");
    }

    void bindParams(Viewport vp, double _thickness, FloatBuffer mvp) {
        FloatBuffer values = screenBuffer.begin(mvp);
        values.put(vp.glslArray).put((float) (0.5 * _thickness));
        values.put(0).put(0).put(0); // std140 padding
        screenBuffer.upload();
    }

    void bindOpaquePass(boolean opaque) {
        GL.glUniform1i(opaquePassRef, opaque ? 1 : 0);
    }

}
