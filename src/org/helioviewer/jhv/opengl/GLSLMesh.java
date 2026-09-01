package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.opengl.model.ModelMesh;

final class GLSLMesh extends VAO {

    private static final int POSITION_BYTES = 3 * Float.BYTES;
    private static final int NORMAL_BYTES = 3 * Float.BYTES;
    private static final int COLOR_BYTES = 4;
    private static final int TEX_COORD_BYTES = 2 * Float.BYTES;

    private final ModelMesh data;
    private final int indexCount;
    private final int stride;

    private GLBO indexBuffer;

    GLSLMesh(ModelMesh _data) {
        super(false, attributes(_data.hasNormals(), _data.hasTextureCoordinates()));
        if (_data.primitive() != ModelMesh.Primitive.TRIANGLES)
            throw new IllegalArgumentException("GLSLMesh requires triangle geometry");
        data = _data;
        indexCount = data.indices().remaining();
        stride = stride(data.hasNormals(), data.hasTextureCoordinates());
    }

    private static VertexAttribute[] attributes(boolean normals, boolean textured) {
        int colorOffset = POSITION_BYTES + (normals ? NORMAL_BYTES : 0);
        int texCoordOffset = colorOffset + COLOR_BYTES;
        int stride = stride(normals, textured);
        VertexAttribute position = VertexAttribute.floats(0, 3, stride, 0);
        VertexAttribute color = VertexAttribute.normalizedUnsignedBytes(1, 4, stride, colorOffset);
        VertexAttribute normal = VertexAttribute.floats(2, 3, stride, POSITION_BYTES);
        VertexAttribute texCoord = VertexAttribute.floats(3, 2, stride, texCoordOffset);
        if (normals)
            return textured ? new VertexAttribute[]{position, color, normal, texCoord} : new VertexAttribute[]{position, color, normal};
        return textured ? new VertexAttribute[]{position, color, texCoord} : new VertexAttribute[]{position, color};
    }

    private static int stride(boolean normals, boolean textured) {
        return POSITION_BYTES + (normals ? NORMAL_BYTES : 0) + COLOR_BYTES + (textured ? TEX_COORD_BYTES : 0);
    }

    @Override
    public void init() {
        if (indexBuffer != null)
            return;

        super.init();
        try {
            uploadVertexBuffer(interleaveVertices());
            bind();
            indexBuffer = new GLBO(GL.ELEMENT_ARRAY_BUFFER, GL.STATIC_DRAW);
            indexBuffer.setBufferData(data.indices());
        } catch (RuntimeException | Error e) {
            dispose();
            throw e;
        }
    }

    private ByteBuffer interleaveVertices() {
        int vertexCount = data.vertexCount();
        ByteBuffer buffer = BufferUtils.newByteBuffer(Math.multiplyExact(vertexCount, stride));
        FloatBuffer positions = data.positions();
        FloatBuffer normals = data.normals();
        ByteBuffer colors = data.colors();
        FloatBuffer texCoords = data.texCoords();

        for (int i = 0; i < vertexCount; i++) {
            buffer.putFloat(positions.get(3 * i)).putFloat(positions.get(3 * i + 1)).putFloat(positions.get(3 * i + 2));
            if (normals != null)
                buffer.putFloat(normals.get(3 * i)).putFloat(normals.get(3 * i + 1)).putFloat(normals.get(3 * i + 2));
            buffer.put(colors.get(4 * i)).put(colors.get(4 * i + 1)).put(colors.get(4 * i + 2)).put(colors.get(4 * i + 3));
            if (texCoords != null)
                buffer.putFloat(texCoords.get(2 * i)).putFloat(texCoords.get(2 * i + 1));
        }
        return buffer.flip();
    }

    void render() {
        bind();
        GL.glDrawElements(GL.TRIANGLES, indexCount, GL.UNSIGNED_INT, 0);
    }

    @Override
    public void dispose() {
        if (indexBuffer != null) {
            indexBuffer.delete();
            indexBuffer = null;
        }
        super.dispose();
    }

}
