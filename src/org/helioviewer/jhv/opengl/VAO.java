package org.helioviewer.jhv.opengl;

import java.nio.Buffer;

class VAO {

    private final VertexAttribute[] attributes;
    private final int usage;

    private GLBO vertexBuffer;
    private int vaoID = -1;
    private boolean inited;

    VAO(boolean dynamic, VertexAttribute... _attributes) {
        attributes = _attributes;
        usage = dynamic ? GL.DYNAMIC_DRAW : GL.STATIC_DRAW;
    }

    public void init() {
        if (!inited) {
            inited = true;

            vaoID = GL.glGenVertexArray();
            vertexBuffer = new GLBO(GL.ARRAY_BUFFER, usage);

            GL.glBindVertexArray(vaoID);
            vertexBuffer.bind();
            for (VertexAttribute attribute : attributes)
                attribute.enable();
        }
    }

    public void dispose() {
        if (inited) {
            inited = false;
            GL.glDeleteVertexArray(vaoID);
            vaoID = -1;

            vertexBuffer.delete();
            vertexBuffer = null;
        }
    }

    protected void uploadVertexBuffer(Buffer buffer) {
        vertexBuffer.setBufferData(buffer);
    }

    protected void bind() {
        GL.glBindVertexArray(vaoID);
    }

}
