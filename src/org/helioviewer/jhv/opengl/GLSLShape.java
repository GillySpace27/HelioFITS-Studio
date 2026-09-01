package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class GLSLShape extends VAO implements GLSLVertexReceiver {

    private int count;

    public GLSLShape(boolean _dynamic) {
        super(_dynamic,
                new VertexAttribute[]{VertexAttribute.floats(0, 4, 0, 0)},
                new VertexAttribute[]{VertexAttribute.normalizedUnsignedBytes(1, 4, 0, 0)});
    }

    @Override
    public void upload(BufVertex vexBuf) {
        count = vexBuf.getCount();
        upload(vexBuf.toVertexBuffer(), vexBuf.toColorBuffer());
    }

    @Override
    public void upload(DirectBufVertex vexBuf) {
        count = vexBuf.count();
        upload(vexBuf.vertexBuffer(), vexBuf.colorBuffer());
    }

    private void upload(ByteBuffer vertices, ByteBuffer colors) {
        if (count == 0)
            return;
        vertexBuffer(0).setBufferData(vertices.remaining(), vertices);
        vertexBuffer(1).setBufferData(colors.remaining(), colors);
    }

    public void renderPoints(double factor) {
        renderPoints(factor, Transform.get());
    }

    void renderPoints(double factor, FloatBuffer mvp) {
        if (count == 0)
            return;

        GLSLShapeShader.point.use();
        GLSLShapeShader.point.bindParams(factor);
        GLSLShapeShader.point.bindMVP(mvp);

        bind();

        GLSLShapeShader.point.bindOpaquePass(true);
        GL.glDrawArrays(GL.POINTS, 0, count);

        GLSLShapeShader.point.bindOpaquePass(false);
        GL.glDepthMask(false);
        GL.glDrawArrays(GL.POINTS, 0, count);
        GL.glDepthMask(true);
    }

    public void renderShape(int mode) {
        if (count == 0)
            return;

        GLSLShapeShader.shape.use();
        GLSLShapeShader.shape.bindMVP(Transform.get());

        bind();
        GL.glDrawArrays(mode, 0, count);
    }

}
