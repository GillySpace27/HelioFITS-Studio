#version 300 es

layout(location = 0) in vec3 vertex;
layout(location = 1) in vec4 color;
layout(location = 2) in vec3 normal;
layout(location = 3) in vec2 textureCoordinate;

out vec4 vertexColor;
out vec3 worldNormal;
out vec2 texturePosition;

layout(std140) uniform FrameBlock {
    mat4 worldToClip;
    vec3 lightDirection;
} frame;

void main(void) {
    gl_Position = frame.worldToClip * vec4(vertex, 1.);
    vertexColor = color;
    worldNormal = normal;
    texturePosition = textureCoordinate;
}
