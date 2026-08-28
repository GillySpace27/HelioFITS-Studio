void main(void) {
    vec4 color;
    vec2 mapPos = getNormalizedMapPos();

    float angle = mapPos.x * TWOPI;
    float radialCoordinate = unwarpRadius(mapPos.y);
    // Below the limb the vertical coordinate is heliocentric angle from the sub-observer
    // point (a latitudinal map about the observer axis), not plane radius. Sampling stays
    // by line of sight, so this is a pure radial remap of the disk band; the branches agree
    // at the limb, where gamma = 90 degrees projects to plane radius 1 exactly.
    if (radialCoordinate < 1.) {
        float gamma = radialCoordinate * HALFPI;
        float d = projection[0].observerDistance;
        radialCoordinate = sin(gamma) * d / (d - cos(gamma));
    }
    vec2 hpcXY = radialCoordinate * vec2(-sin(angle), cos(angle));
    vec2 helioprojective = hpcXYToHelioprojective(hpcXY, projection[0].observerDistance);
    float enhancementFactor;
    bool diffMode = display.isDiff != NODIFFERENCE;
    clipHpcGeometry(hpcXY);
    vec2 texCoord = sampleHpcTexcoord(wcs[0], projection[0], helioprojective, hpcXY, wcs[0].deltaT, pv0, enhancementFactor);
    if (!diffMode) {
        color = getColor(texCoord, texCoord, enhancementFactor);
    } else {
        vec2 diffHelioprojective = hpcXYToHelioprojective(hpcXY, projection[1].observerDistance);
        float diffEnhancementFactor;
        vec2 diffTexCoord = sampleHpcTexcoord(wcs[1], projection[1], diffHelioprojective, hpcXY, wcs[1].deltaT, pv1, diffEnhancementFactor);
        color = getColor(texCoord, diffTexCoord, max(enhancementFactor, diffEnhancementFactor));
    }
    outColor = color;
}
