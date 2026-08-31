package org.helioviewer.jhv.opengl;

interface GLSLVertexReceiver {

    void upload(BufVertex vexBuf);

    void upload(DirectBufVertex vexBuf);

    default void uploadAndClear(BufVertex vexBuf) {
        upload(vexBuf);
        vexBuf.clear();
    }

}
