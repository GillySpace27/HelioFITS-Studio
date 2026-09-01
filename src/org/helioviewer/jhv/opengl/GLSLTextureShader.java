package org.helioviewer.jhv.opengl;

class GLSLTextureShader extends GLSLShader {

    static final GLSLTextureShader texture = new GLSLTextureShader("/glsl/texture.vert", "/glsl/texture.frag");
    static final GLSLTextureShader sdf = new GLSLTextureShader("/glsl/texture.vert", "/glsl/textureSdf.frag");

    private int refModelViewProjectionMatrix;
    private int colorRef;
    private int unitRangeRef = -1;

    private GLSLTextureShader(String vertex, String fragment) {
        super(vertex, fragment);
    }

    public static void init() {
        texture._init(false);
        sdf._init(false);
    }

    public static void dispose() {
        texture._dispose();
        sdf._dispose();
    }

    @Override
    protected void initUniforms(int id) {
        refModelViewProjectionMatrix = requiredUniform(id, "ModelViewProjectionMatrix");
        colorRef = requiredUniform(id, "color");
        if (this == sdf)
            unitRangeRef = requiredUniform(id, "unitRange");
        setTextureUnit(id, "image", GLTexture.Unit.THREE);
    }

    void bindParams(float[] color) {
        GL.glUniform4fv(colorRef, color);
    }

    void bindSdfParams(float[] color, float unitRangeX, float unitRangeY) {
        bindParams(color);
        GL.glUniform2f(unitRangeRef, unitRangeX, unitRangeY);
    }

    void bindMVP() {
        GL.glUniformMatrix4fv(refModelViewProjectionMatrix, false, Transform.get());
    }

}
