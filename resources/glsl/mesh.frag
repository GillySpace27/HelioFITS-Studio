#version 300 es

precision highp float;

in vec4 vertexColor;
in vec3 viewNormal;
in vec2 texturePosition;
out vec4 outColor;

layout(std140) uniform MaterialBlock {
    vec4 baseColor;
    float alphaCutoff;
    float alphaMode;
    float hasTexture;
    float unlit;
} material;

uniform sampler2D baseColorTexture;

const float ALPHA_OPAQUE = 0.;
const float ALPHA_MASK = 1.;

void main(void) {
    vec4 color = material.baseColor * vertexColor;
    if (material.hasTexture != 0.)
        color *= texture(baseColorTexture, texturePosition);

    if (material.unlit == 0.) {
        vec3 normal = normalize(viewNormal);
        if (!gl_FrontFacing)
            normal = -normal;
        color.rgb *= 0.3 + 0.7 * max(normal.z, 0.);
    }

    if (material.alphaMode == ALPHA_OPAQUE) {
        color.a = 1.;
    } else if (material.alphaMode == ALPHA_MASK) {
        if (color.a < material.alphaCutoff)
            discard;
        color.a = 1.;
    } else {
        color.rgb *= color.a;
    }
    outColor = color;
}
