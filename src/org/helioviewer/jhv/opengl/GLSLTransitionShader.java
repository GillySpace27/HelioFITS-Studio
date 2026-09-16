package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;

// The fading-out snapshot GLRenderer draws over a new projection while a projection switch is
// animating. One full-screen textured quad, alpha-blended with the app's global premultiplied
// blend function -- see transition.frag for why that makes a plain multiply the correct fade.
//
// It owns its quad rather than borrowing the image shaders' one: that quad belongs to
// GLSLScreenShader, whose programs all share the screen uniform block, and this program has no
// business declaring that block just to reach the geometry.
final class GLSLTransitionShader extends GLSLShader {

    private static final GLSLTransitionShader instance = new GLSLTransitionShader();

    private static final VertexArrayObject quad = new VertexArrayObject(false, VertexAttribute.floats(0, 4, 0, 0));
    private static final FloatBuffer vertices = BufferUtils.newFloatBuffer(16).put(new float[]{-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1}).flip();

    private int fadeAlphaRef;

    private GLSLTransitionShader() {
        super("/glsl/transition.vert", "/glsl/transition.frag");
    }

    static void init() {
        try {
            instance._init();
            quad.init();
            quad.uploadVertexBuffer(vertices);
        } catch (RuntimeException | Error e) {
            dispose();
            throw e;
        }
    }

    static void dispose() {
        instance._dispose();
        quad.dispose();
    }

    @Override
    protected void initUniforms(int id) {
        fadeAlphaRef = requiredUniform(id, "fadeAlpha");
        setTextureUnit(id, "image", GLTexture.Unit.ZERO);
    }

    static void render(int textureId, double fadeAlpha) {
        instance.use();
        GL.glActiveTexture(GL.TEXTURE0);
        GL.glBindTexture(GL.TEXTURE_2D, textureId);
        GL.glUniform1f(instance.fadeAlphaRef, (float) fadeAlpha);
        quad.bind();
        GL.glDrawArrays(GL.TRIANGLE_STRIP, 0, 4);
    }
}
