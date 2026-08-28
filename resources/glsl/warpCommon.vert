// Shared vertex-stage warp, prepended to the overlay shaders (line, point, shape) the way
// solarCommon.frag is prepended to the solar fragment shaders.
//
// Everything drawn in world space -- point clouds, PFSS field lines, the grid, FOV boxes,
// annotations -- has to undergo the same radial compression as the imagery, or the scene is
// only self-consistent at lambda = 1 where the warp happens to be the identity. Applying it
// here means one function covers every overlay, instead of each layer learning about the warp.
//
// Twin of display/WarpGeometry.java and of warpSurface.vert. Keep the three in step.

layout(std140) uniform WarpBlock {
    float lambda;
    float limb;
    float outerRadius;
    float enabled; // 0 = pass through untouched, which is every projection except Helioradial
} warp;

// Physical heliocentric radius to its warped position. Mirrors
// MapScale.BoxCoxRadialScale.toUnitY, then scales back out by the outer radius so that the
// projection's own edge is a fixed point and lambda = 1 is the exact identity.
float warpRadius(const float r) {
    float outerRadius = warp.outerRadius;
    float limbPos = warp.limb > 0. ? warp.limb : 1. / outerRadius;
    float unit;
    if (outerRadius <= 1. || r <= 1.) {
        unit = r * limbPos;
    } else {
        float u = warp.lambda == 0.
                ? log(r) / log(outerRadius)
                : (pow(r, warp.lambda) - 1.) / (pow(outerRadius, warp.lambda) - 1.);
        unit = limbPos + u * (1. - limbPos);
    }
    return unit * outerRadius;
}

// Direction preserved, distance rescaled. Overlays keep their true positions under the same
// law rather than being flattened onto the imaged surface, so a structure that genuinely lies
// on that surface lands on it and one that does not visibly floats off it.
vec3 warpWorld(const vec3 p) {
    if (warp.enabled == 0.)
        return p;
    float r = length(p);
    if (r <= 0.)
        return p;
    return p * (warpRadius(r) / r);
}

vec4 warpWorld(const vec4 p) {
    return vec4(warpWorld(p.xyz), p.w);
}
