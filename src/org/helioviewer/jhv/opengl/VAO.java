package org.helioviewer.jhv.opengl;

class VAO {

    private final VertexAttribute[][] attributesByBuffer;
    private final GLBO[] vertexBuffers;
    private final int usage;

    private int vaoID = -1;
    private boolean inited;

    VAO(boolean dynamic, VertexAttribute[]... _attributesByBuffer) {
        attributesByBuffer = _attributesByBuffer;
        vertexBuffers = new GLBO[attributesByBuffer.length];
        usage = dynamic ? GL.DYNAMIC_DRAW : GL.STATIC_DRAW;
    }

    public void init() {
        if (!inited) {
            inited = true;

            vaoID = GL.glGenVertexArray();
            for (int i = 0; i < vertexBuffers.length; i++)
                vertexBuffers[i] = new GLBO(GL.ARRAY_BUFFER, usage);

            GL.glBindVertexArray(vaoID);
            for (int i = 0; i < vertexBuffers.length; i++) {
                vertexBuffers[i].bind();
                for (VertexAttribute attribute : attributesByBuffer[i])
                    attribute.enable();
            }
        }
    }

    public void dispose() {
        if (inited) {
            inited = false;
            GL.glDeleteVertexArray(vaoID);
            vaoID = -1;

            for (int i = 0; i < vertexBuffers.length; i++) {
                vertexBuffers[i].delete();
                vertexBuffers[i] = null;
            }
        }
    }

    protected GLBO vertexBuffer(int index) {
        return vertexBuffers[index];
    }

    protected void bind() {
        GL.glBindVertexArray(vaoID);
    }

}
