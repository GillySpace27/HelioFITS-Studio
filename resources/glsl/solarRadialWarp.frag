// Sample one layer at a helioprojective direction, taking the surface-map path for CAR/CEA.
// Surface maps carry no off-limb data, so a sight line that misses the Sun discards instead of
// falling back to the plane of sky; see sampleSurfaceMapTexcoord in solarCommon.frag.
vec2 sampleWarpTexcoord(const WCS wcs, const ProjectionParams projection, const vec2 helioprojective, const vec2 hpcXY, const float dt, const float[6] PV, out float enhancementFactor) {
    if (isSurfaceMapProjection(projection)) {
        vec2 surfaceTexCoord;
        if (!sampleSurfaceMapTexcoord(helioprojective, wcs, projection, PV, surfaceTexCoord))
            discard;
        enhancementFactor = 1.;
        return surfaceTexCoord;
    }
    return sampleHpcTexcoord(wcs, projection, helioprojective, hpcXY, dt, PV, enhancementFactor);
}

void main(void) {
    vec4 color;
    vec2 w = getViewPosition();
    float t = 2. * length(w);
    if (t > 1. || t == 0.)
        discard;

    float angle = atan(-w.x, w.y);
    if (angle < 0.)
        angle += TWOPI;
    clamp_coord(vec2(angle / TWOPI, t));

    float radialCoordinate = unwarpRadius(t);
    vec2 hpcXY = (radialCoordinate / length(w)) * w;
    vec2 helioprojective = hpcXYToHelioprojective(hpcXY, projection[0].observerDistance);
    float enhancementFactor;
    bool diffMode = display.isDiff != NODIFFERENCE;
    clipHpcGeometry(hpcXY);
    vec2 texCoord = sampleWarpTexcoord(wcs[0], projection[0], helioprojective, hpcXY, wcs[0].deltaT, pv0, enhancementFactor);
    if (!diffMode) {
        color = getColor(texCoord, texCoord, enhancementFactor);
    } else {
        vec2 diffHelioprojective = hpcXYToHelioprojective(hpcXY, projection[1].observerDistance);
        float diffEnhancementFactor;
        vec2 diffTexCoord = sampleWarpTexcoord(wcs[1], projection[1], diffHelioprojective, hpcXY, wcs[1].deltaT, pv1, diffEnhancementFactor);
        color = getColor(texCoord, diffTexCoord, max(enhancementFactor, diffEnhancementFactor));
    }
    outColor = color;
}
