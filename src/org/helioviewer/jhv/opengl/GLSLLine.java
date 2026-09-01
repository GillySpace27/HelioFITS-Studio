package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.Viewport;

public class GLSLLine extends VAO implements GLSLVertexReceiver {

    public static final double LINEWIDTH_BASIC = 0.002;

    private int count;

    public GLSLLine(boolean _dynamic) {
        super(_dynamic,
                positionAttribute(0, 0), colorAttribute(1, 0),
                positionAttribute(2, 1), colorAttribute(3, 1),
                positionAttribute(4, 2), colorAttribute(5, 2),
                positionAttribute(6, 3), colorAttribute(7, 3));
    }

    private static VertexAttribute positionAttribute(int index, int vertex) {
        return VertexAttribute.instancedFloats(index, BufVertex.POSITION_COMPONENTS, BufVertex.BYTES_PER_VERTEX, vertex * BufVertex.BYTES_PER_VERTEX);
    }

    private static VertexAttribute colorAttribute(int index, int vertex) {
        return VertexAttribute.instancedNormalizedUnsignedBytes(index, BufVertex.COLOR_COMPONENTS, BufVertex.BYTES_PER_VERTEX,
                vertex * BufVertex.BYTES_PER_VERTEX + BufVertex.POSITION_BYTES);
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
        if (count < 4) {
            Log.warn("GLSLLine requires at least two visible vertices padded by transparent sentinels; count=" + count + ", emitter=" + getEmitter());
            count = 0;
        } else
            count -= 3;
    }

    private static String getEmitter() {
        String self = GLSLLine.class.getName();
        String receiver = GLSLVertexReceiver.class.getName();
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().equals(self) && !frame.getClassName().equals(receiver))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                .orElse("|unknown|"));
    }

    public void renderLine(Viewport vp, double thickness) {
        renderLine(vp, thickness, Transform.get());
    }

    void renderLine(Viewport vp, double thickness, FloatBuffer mvp) {
        if (count == 0)
            return;

        GLSLLineShader.line.use();
        GLSLLineShader.line.bindParams(vp, thickness, mvp);

        bind();

        // Let fully opaque line cores occlude later geometry. The second pass
        // adds translucent colors and antialiasing without writing their depth.
        GLSLLineShader.line.bindOpaquePass(true);
        GL.glDrawArraysInstanced(GL.TRIANGLE_STRIP, 0, 4, count);

        GLSLLineShader.line.bindOpaquePass(false);
        GL.glDepthMask(false);
        GL.glDrawArraysInstanced(GL.TRIANGLE_STRIP, 0, 4, count);
        GL.glDepthMask(true);
    }

}
