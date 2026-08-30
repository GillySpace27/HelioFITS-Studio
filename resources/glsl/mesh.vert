#version 300 es

layout(location = 0) in vec3 vertex;
layout(location = 1) in vec4 color;
layout(location = 2) in vec3 normal;
layout(location = 3) in vec2 textureCoordinate;

out vec4 vertexColor;
out vec3 viewNormal;
out vec2 texturePosition;

uniform mat4 modelViewProjectionMatrix;
uniform mat3 normalMatrix;

void main(void) {
    gl_Position = modelViewProjectionMatrix * vec4(vertex, 1.);
    vertexColor = color;
    viewNormal = normalMatrix * normal;
    texturePosition = textureCoordinate;
}
