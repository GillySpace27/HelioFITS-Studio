package org.helioviewer.jhv.opengl.model;

import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.time.JHVTime;

public record ModelScene(String name, @Nullable JHVTime time, List<ModelMesh> meshes, List<ModelMaterial> materials,
                         List<ModelTexture> textures) {

    public ModelScene {
        meshes = List.copyOf(meshes);
        materials = List.copyOf(materials);
        textures = List.copyOf(textures);
        if (meshes.isEmpty())
            throw new IllegalArgumentException("Model scene has no meshes");
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
    }

}
