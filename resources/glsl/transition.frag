#version 300 es

precision highp float;

in vec2 texCoord;
out vec4 outColor;

uniform sampler2D image;
uniform float fadeAlpha;

// The blend function is the app's global premultiplied one (GL.ONE, GL.ONE_MINUS_SRC_ALPHA,
// set once in GLRenderer.init). Scaling the whole premultiplied texel by fadeAlpha is the
// correct way to fade a premultiplied image toward transparent.
void main(void) {
    outColor = texture(image, texCoord) * fadeAlpha;
}
