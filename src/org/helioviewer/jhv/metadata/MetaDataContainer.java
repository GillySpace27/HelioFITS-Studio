package org.helioviewer.jhv.metadata;

import java.util.Optional;

import javax.annotation.Nonnull;

/**
 * Whatever a FITS-like header can be asked for, as keys.
 *
 * <p>Public because {@link FitsMetaData}'s constructor is, and takes one of these: the two
 * together are how anything outside this package reads a header the way the application reads it,
 * rather than growing a second interpreter that has to learn the same conventions again. The cache
 * scanner is the first caller from outside; {@code extra/test/MapMetaDataContainer.java} was the
 * first from within.
 */
public interface MetaDataContainer {

    @Nonnull
    Optional<String> getString(String key);

    @Nonnull
    Optional<Long> getLong(String key);

    @Nonnull
    Optional<Double> getDouble(String key);

    @Nonnull
    String getRequiredString(String key);

    long getRequiredLong(String key);

    double getRequiredDouble(String key);

}
