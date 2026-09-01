package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

class GLSLShapeShader extends GLSLShader {

    static final GLSLShapeShader point = new GLSLShapeShader("/glsl/point.vert", "/glsl/point.frag");
    static final GLSLShapeShader shape = new GLSLShapeShader("/glsl/shape.vert", "/glsl/shape.frag");

    private int refModelViewProjectionMatrix;
    private int factorRef = -1;
    private int opaquePassRef = -1;

    private GLSLShapeShader(String vertex, String fragment) {
        super(vertex, fragment);
    }

    public static void init() {
        try {
            point._init();
            shape._init();
        } catch (RuntimeException | Error e) {
            dispose();
            throw e;
        }
    }

    public static void dispose() {
        point._dispose();
        shape._dispose();
    }

    @Override
    protected void initUniforms(int id) {
        refModelViewProjectionMatrix = requiredUniform(id, "ModelViewProjectionMatrix");
        if (this == point) {
            factorRef = requiredUniform(id, "factor");
            opaquePassRef = requiredUniform(id, "opaquePass");
        }
    }

    void bindParams(double _factor) {
        GL.glUniform1f(factorRef, (float) _factor);
    }

    void bindMVP(FloatBuffer mvp) {
        GL.glUniformMatrix4fv(refModelViewProjectionMatrix, false, mvp);
    }

    void bindOpaquePass(boolean opaque) {
        GL.glUniform1i(opaquePassRef, opaque ? 1 : 0);
    }

}
