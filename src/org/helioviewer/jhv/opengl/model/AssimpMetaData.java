package org.helioviewer.jhv.opengl.model;

import java.io.IOException;
import java.net.URI;

import javax.annotation.Nullable;

import org.helioviewer.jhv.metadata.HeliocentricCartesianMetaData;

import org.lwjgl.assimp.AIMetaData;
import org.lwjgl.assimp.AIMetaDataEntry;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryUtil;

import com.google.common.primitives.UnsignedLong;

final class AssimpMetaData implements HeliocentricCartesianMetaData.Source {

    private final URI source;
    private final @Nullable AIMetaData metadata;

    AssimpMetaData(URI _source, @Nullable AIMetaData _metadata) {
        source = _source;
        metadata = _metadata;
    }

    @Override
    public boolean contains(String key) {
        return find(key) != null;
    }

    @Override
    public String getString(String key) throws IOException {
        AIMetaDataEntry entry = required(key);
        if (entry.mType() != Assimp.AI_AISTRING)
            throw error(key + " must be a string");
        return AIString.create(MemoryUtil.memAddress(entry.mData(AIString.SIZEOF))).dataString();
    }

    @Override
    public double getDouble(String key) throws IOException {
        AIMetaDataEntry entry = required(key);
        double value = switch (entry.mType()) {
            case Assimp.AI_INT32 -> entry.mData(Integer.BYTES).getInt();
            case Assimp.AI_UINT32 -> Integer.toUnsignedLong(entry.mData(Integer.BYTES).getInt());
            case Assimp.AI_INT64 -> entry.mData(Long.BYTES).getLong();
            case Assimp.AI_UINT64 -> UnsignedLong.fromLongBits(entry.mData(Long.BYTES).getLong()).doubleValue();
            case Assimp.AI_FLOAT -> entry.mData(Float.BYTES).getFloat();
            case Assimp.AI_DOUBLE -> entry.mData(Double.BYTES).getDouble();
            default -> throw error(key + " must be a JSON number");
        };
        if (!Double.isFinite(value))
            throw error(key + " must be finite");
        return value;
    }

    @Override
    public IOException error(String message) {
        return new IOException(source + ": " + message);
    }

    private AIMetaDataEntry required(String key) throws IOException {
        AIMetaDataEntry entry = find(key);
        if (entry == null)
            throw error("missing " + key + " in solar metadata");
        return entry;
    }

    private @Nullable AIMetaDataEntry find(String key) {
        if (metadata == null)
            return null;
        for (int i = 0; i < metadata.mNumProperties(); i++) {
            if (metadata.mKeys().get(i).dataString().equals(key))
                return metadata.mValues().get(i);
        }
        return null;
    }
}
