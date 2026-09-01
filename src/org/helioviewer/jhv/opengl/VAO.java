package org.helioviewer.jhv.opengl;

import java.nio.Buffer;

class VAO {

    private final VertexAttribute[] attributes;
    private final int usage;

    private GLBO vertexBuffer;
    private int vaoID = -1;

    VAO(boolean dynamic, VertexAttribute... _attributes) {
        attributes = _attributes;
        usage = dynamic ? GL.DYNAMIC_DRAW : GL.STATIC_DRAW;
    }

    public void init() {
        if (vertexBuffer != null)
            return;

        GLBO newVertexBuffer = new GLBO(GL.ARRAY_BUFFER, usage);
        int newVaoID = -1;
        try {
            newVaoID = GL.glGenVertexArray();
            GL.glBindVertexArray(newVaoID);
            newVertexBuffer.bind();
            for (VertexAttribute attribute : attributes)
                attribute.enable();
        } catch (RuntimeException | Error e) {
            if (newVaoID != -1)
                GL.glDeleteVertexArray(newVaoID);
            newVertexBuffer.delete();
            throw e;
        }

        vertexBuffer = newVertexBuffer;
        vaoID = newVaoID;
    }

    public void dispose() {
        if (vertexBuffer == null)
            return;

        GL.glDeleteVertexArray(vaoID);
        vaoID = -1;
        vertexBuffer.delete();
        vertexBuffer = null;
    }

    protected void uploadVertexBuffer(Buffer buffer) {
        vertexBuffer.setBufferData(buffer);
    }

    protected void bind() {
        GL.glBindVertexArray(vaoID);
    }

}
