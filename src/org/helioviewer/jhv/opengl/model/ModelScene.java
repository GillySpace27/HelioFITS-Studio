package org.helioviewer.jhv.opengl.model;

import java.util.List;

public record ModelScene(String name, List<ModelMesh> meshes, List<ModelInstance> instances) {

    public ModelScene {
        meshes = List.copyOf(meshes);
        instances = List.copyOf(instances);
        if (meshes.isEmpty())
            throw new IllegalArgumentException("Model scene has no meshes");
        if (instances.isEmpty())
            throw new IllegalArgumentException("Model scene has no mesh instances");

        for (ModelInstance instance : instances) {
            if (instance.meshIndex() < 0 || instance.meshIndex() >= meshes.size())
                throw new IllegalArgumentException("Invalid mesh index: " + instance.meshIndex());
        }
    }

}
