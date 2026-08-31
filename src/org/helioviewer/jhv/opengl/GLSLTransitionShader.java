package org.helioviewer.jhv.opengl;

// The fading-out snapshot GLRenderer draws over a new projection while a projection switch is
// animating. One full-screen textured quad (GLSLSolar.quad, the same one every full-screen
// projection shader already uses), alpha-blended with the app's global premultiplied blend
// function -- see transition.frag for why that makes a plain multiply the correct fade.
final class GLSLTransitionShader extends GLSLShader {

    static final GLSLTransitionShader instance = new GLSLTransitionShader();

    private int fadeAlphaRef;

    private GLSLTransitionShader() {
        super("/glsl/transition.vert", "/glsl/transition.frag");
    }

    static void init() {
        instance._init(false);
    }

    static void dispose() {
        instance._dispose();
    }

    @Override
    protected void initUniforms(int id) {
        fadeAlphaRef = GL.glGetUniformLocation(id, "fadeAlpha");
        setTextureUnit(id, "image", GLTexture.Unit.ZERO);
    }

    static void render(int textureId, double fadeAlpha) {
        instance.use();
        GL.glActiveTexture(GL.TEXTURE0);
        GL.glBindTexture(GL.TEXTURE_2D, textureId);
        GL.glUniform1f(instance.fadeAlphaRef, (float) fadeAlpha);
        GLSLSolar.quad.render();
    }
}
