package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.opengl.model.ModelMesh;

final class GLSLMesh extends VAO1 {

    private static final int POSITION_BYTES = 3 * Float.BYTES;
    private static final int NORMAL_BYTES = 3 * Float.BYTES;
    private static final int COLOR_BYTES = 4;
    private static final int TEX_COORD_BYTES = 2 * Float.BYTES;
    private static final int COLOR_OFFSET = POSITION_BYTES + NORMAL_BYTES;
    private static final int TEX_COORD_OFFSET = COLOR_OFFSET + COLOR_BYTES;
    private static final int UNTEXTURED_STRIDE = TEX_COORD_OFFSET;
    private static final int TEXTURED_STRIDE = UNTEXTURED_STRIDE + TEX_COORD_BYTES;

    private final ModelMesh data;
    private final int indexCount;
    private final int stride;

    private GLBO indexBuffer;

    GLSLMesh(ModelMesh _data) {
        super(false, attributes(_data.hasTextureCoordinates()));
        if (_data.primitive() != ModelMesh.Primitive.TRIANGLES)
            throw new IllegalArgumentException("GLSLMesh requires triangle geometry");
        data = _data;
        indexCount = data.indices().remaining();
        stride = data.hasTextureCoordinates() ? TEXTURED_STRIDE : UNTEXTURED_STRIDE;
    }

    private static VAA[] attributes(boolean textured) {
        int stride = textured ? TEXTURED_STRIDE : UNTEXTURED_STRIDE;
        VAA position = new VAA(0, 3, false, stride, 0, 0);
        VAA color = new VAA(1, 4, true, stride, COLOR_OFFSET, 0);
        VAA normal = new VAA(2, 3, false, stride, POSITION_BYTES, 0);
        VAA texCoord = new VAA(3, 2, false, stride, TEX_COORD_OFFSET, 0);
        return textured ? new VAA[]{position, color, normal, texCoord} : new VAA[]{position, color, normal};
    }

    @Override
    public void init() {
        if (indexBuffer != null)
            return;

        super.init();
        try {
            vbo.setBufferData(Math.multiplyExact(data.vertexCount(), stride), interleaveVertices());
            bind();
            indexBuffer = new GLBO(GL.ELEMENT_ARRAY_BUFFER, GL.STATIC_DRAW);
            indexBuffer.setBufferData(Math.multiplyExact(indexCount, Integer.BYTES), data.indices());
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
