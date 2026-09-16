package org.helioviewer.jhv.opengl;

interface GLSLVertexReceiver {

    void upload(BufVertex vertices);

    void upload(DirectBufVertex vertices);

    default void uploadAndClear(BufVertex vertices) {
        upload(vertices);
        vertices.clear();
    }

}
