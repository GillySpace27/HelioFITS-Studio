package org.helioviewer.jhv.opengl.model;

import java.util.List;

public record ModelScene(String name, List<ModelMesh> meshes, List<ModelInstance> instances, List<ModelMaterial> materials,
                         List<ModelTexture> textures) {

    public ModelScene {
        meshes = List.copyOf(meshes);
        instances = List.copyOf(instances);
        materials = List.copyOf(materials);
        textures = List.copyOf(textures);
        if (meshes.isEmpty())
            throw new IllegalArgumentException("Model scene has no meshes");
        if (instances.isEmpty())
            throw new IllegalArgumentException("Model scene has no mesh instances");
        if (materials.isEmpty())
            throw new IllegalArgumentException("Model scene has no materials");

        for (ModelMaterial material : materials) {
            if (material.baseColorTexture() < ModelMaterial.NO_TEXTURE || material.baseColorTexture() >= textures.size())
                throw new IllegalArgumentException("Invalid texture index: " + material.baseColorTexture());
        }
        for (ModelMesh mesh : meshes) {
            if (mesh.materialIndex() >= materials.size())
                throw new IllegalArgumentException("Invalid material index: " + mesh.materialIndex());
            if (materials.get(mesh.materialIndex()).baseColorTexture() != ModelMaterial.NO_TEXTURE && !mesh.hasTextureCoordinates())
                throw new IllegalArgumentException("Textured mesh has no texture coordinates: " + mesh.name());
        }
        for (ModelInstance instance : instances) {
            if (instance.meshIndex() < 0 || instance.meshIndex() >= meshes.size())
                throw new IllegalArgumentException("Invalid mesh index: " + instance.meshIndex());
        }
    }

}
