package org.helioviewer.jhv.opengl.model;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public record ModelInstance(int meshIndex, Matrix4fc transform) {

    public ModelInstance {
        transform = new Matrix4f(transform);
    }

    @Override
    public Matrix4fc transform() {
        return new Matrix4f(transform);
    }

}
