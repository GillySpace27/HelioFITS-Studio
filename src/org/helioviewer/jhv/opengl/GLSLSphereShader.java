package org.helioviewer.jhv.opengl;

final class GLSLSphereShader extends GLSLScreenShader {

    private static final GLSLSphereShader sphere = new GLSLSphereShader();

    private GLSLSphereShader() {
        super("/glsl/sphere.frag");
    }

    static void init() {
        sphere._init();
    }

    static void dispose() {
        sphere._dispose();
    }

    static void render() {
        sphere.use();
        sphere.draw();
    }
}
