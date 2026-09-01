package org.helioviewer.jhv.opengl;

public class GLSLTexture extends VAO {

    private static final int POSITION_COMPONENTS = 4;
    private static final int TEX_COORD_COMPONENTS = 2;
    private static final int POSITION_BYTES = POSITION_COMPONENTS * Float.BYTES;
    private static final int STRIDE = POSITION_BYTES + TEX_COORD_COMPONENTS * Float.BYTES;

    private int count;

    public GLSLTexture() {
        super(true,
                VertexAttribute.floats(0, POSITION_COMPONENTS, STRIDE, 0),
                VertexAttribute.floats(1, TEX_COORD_COMPONENTS, STRIDE, POSITION_BYTES));
    }

    public void setCoord(BufCoord buf) {
        count = buf.getCount();
        if (count == 0)
            return;

        uploadVertexBuffer(buf.toBuffer());
        buf.clear();
    }

    public void renderTexture(int mode, float[] color, int first, int toDraw) {
        if (count == 0 || toDraw > count)
            return;

        GLSLTextureShader shader = GLSLTextureShader.texture;
        shader.use();
        shader.bindParams(color);
        shader.bindMVP();

        bind();
        GL.glDrawArrays(mode, first, toDraw);
    }

    public void renderSdfTexture(int mode, float[] color, float unitRangeX, float unitRangeY, int first, int toDraw) {
        if (count == 0 || toDraw > count)
            return;

        GLSLTextureShader shader = GLSLTextureShader.sdf;
        shader.use();
        shader.bindSdfParams(color, unitRangeX, unitRangeY);
        shader.bindMVP();

        bind();
        GL.glDrawArrays(mode, first, toDraw);
    }

}
