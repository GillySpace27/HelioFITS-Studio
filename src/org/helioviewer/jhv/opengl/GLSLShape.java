package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class GLSLShape extends VertexArrayObject implements GLSLVertexReceiver {

    private int count;

    public GLSLShape(boolean _dynamic) {
        super(_dynamic,
                VertexAttribute.floats(0, BufVertex.POSITION_COMPONENTS, BufVertex.BYTES_PER_VERTEX, 0),
                VertexAttribute.normalizedUnsignedBytes(1, BufVertex.COLOR_COMPONENTS, BufVertex.BYTES_PER_VERTEX, BufVertex.POSITION_BYTES));
    }

    @Override
    public void upload(BufVertex vertices) {
        count = vertices.getCount();
        upload(vertices.toBuffer());
    }

    @Override
    public void upload(DirectBufVertex vertices) {
        count = vertices.count();
        upload(vertices.buffer());
    }

    private void upload(ByteBuffer vertices) {
        if (count == 0)
            return;
        uploadVertexBuffer(vertices);
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
