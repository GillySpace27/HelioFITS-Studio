#version 300 es

layout(location = 0) in vec4 Vertex;
out vec2 texCoord;

void main(void) {
    gl_Position = Vertex;
    texCoord = Vertex.xy * 0.5 + 0.5;
}
