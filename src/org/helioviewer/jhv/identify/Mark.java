package org.helioviewer.jhv.identify;

import javax.annotation.Nullable;

/**
 * Something the canvas can put the identifier's icon on: a name, its kind, where it is in an
 * observer's sky and how big it is. {@code layer} names the image layer whose observer sees it,
 * or is null for the first target (stars, datasets). {@code angularRadius} is in radians; zero is a
 * point, which the icon enlarges to a visible ring.
 */
public record Mark(String key, Kind kind, @Nullable String layer, Identifier.Probe where, double angularRadius) {}
