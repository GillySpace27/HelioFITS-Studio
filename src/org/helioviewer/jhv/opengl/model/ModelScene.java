package org.helioviewer.jhv.opengl.model;

import java.util.List;

import org.helioviewer.jhv.time.JHVTime;

public record ModelScene(String name, JHVTime time, List<ModelMesh> meshes, List<ModelMaterial> materials,
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
            ModelMaterial material = materials.get(mesh.materialIndex());
            if (material.baseColorTexture() != ModelMaterial.NO_TEXTURE) {
                if (mesh.primitive() != ModelMesh.Primitive.TRIANGLES)
                    throw new IllegalArgumentException("Only triangle meshes can have textures: " + mesh.name());
                if (!mesh.hasTextureCoordinates())
                    throw new IllegalArgumentException("Textured mesh has no texture coordinates: " + mesh.name());
            }
            if (mesh.primitive() == ModelMesh.Primitive.TRIANGLES) {
                if (material.unlit() && mesh.hasNormals())
                    throw new IllegalArgumentException("Unlit triangle mesh contains normals: " + mesh.name());
                if (!material.unlit() && !mesh.hasNormals())
                    throw new IllegalArgumentException("Lit triangle mesh has no normals: " + mesh.name());
            }
        }
    }

}
