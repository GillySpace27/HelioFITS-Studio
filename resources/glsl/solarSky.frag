// The observer's sky, laid flat about a steerable direction. See SkyMap.java, which carries the
// same geometry for the overlays; the two have to agree or the grid drifts off the picture.
//
// Everything here is in the observer's frame: the observer at (0, 0, D), the Sun at the origin, so
// the direction toward the Sun is -z and skyLook = (0, 0) reproduces the ordinary Sun-centred view.

// (helioprojective longitude, latitude, projection code). The code matches SkyProjection's ordinal:
// 0 gnomonic (TAN), 1 stereographic (STG), 2 azimuthal equidistant (ARC).
uniform vec3 skyLook;

// The radial scale this sky is composed with: (outer radius in solar radii, limb position,
// Box-Cox lambda). Off when the outer radius is not positive.
//
// Composition means the dome shows the picture the primary mode draws, rather than the sky as it
// is: a dome pixel's elongation is read as that mode's PAGE radius, undone through its radial
// scale to the heliocentric radius it stands for, and turned back into the elongation whose data
// belongs there. The field edge is the fixed point (page radius 1 is the true elongation of the
// outer radius), so composed and uncomposed agree at the rim and differ inside, which is the warp
// showing up as a change of angular scale. Where along the line of sight the radius is measured
// is the surface model, so with the Thomson sphere selected this is the Thomson-sphere placement
// drawn on the celestial sphere, which is what the sky is.
uniform vec3 skyWarp;
uniform float surfaceModel;

vec3 skyLookRay(const float lon, const float lat) {
    float cosLat = cos(lat);
    return vec3(sin(lon) * cosLat, sin(lat), -cos(lon) * cosLat);
}

// The one line that separates the three projections: how far from the centre of the page a point
// this many radians from the centre of the field is drawn. Inverted here, page to sky.
float skyAngleFromRadius(const float radius, const float code) {
    if (code < .5)
        return atan(radius);            // TAN: radius = tan(rho), diverges at 90 degrees
    if (code < 1.5)
        return 2. * atan(.5 * radius);  // STG: radius = 2 tan(rho/2)
    return radius;                      // ARC: radius = rho, constant angular scale
}

float skyMaxAngle(const float code) {
    if (code < .5)
        return radians(85.);
    if (code < 1.5)
        return radians(175.);
    return radians(180.);
}

void main(void) {
    vec2 mapPos = getNormalizedMapPos();
    // The map coordinate is the WCS native radial coordinate in degrees, so the same linear
    // span-then-radians the other flat modes use lands it in the projection plane directly.
    vec2 page = normalizedMapToHelioprojective(mapPos);

    float code = skyLook.z;
    float radius = length(page);
    float rho = skyAngleFromRadius(radius, code);
    if (rho > skyMaxAngle(code))
        discard;

    float lon = skyLook.x;
    float lat = skyLook.y;
    vec3 look = skyLookRay(lon, lat);
    vec3 ray;
    if (radius < 1e-9) {
        ray = look; // dead centre: the azimuth is undefined and the offset is zero anyway
    } else {
        vec3 east = vec3(cos(lon), 0., sin(lon));
        vec3 north = vec3(-sin(lon) * sin(lat), cos(lat), cos(lon) * sin(lat));
        vec2 u = page / radius;
        ray = cos(rho) * look + sin(rho) * (u.x * east + u.y * north);
    }
    // Only the sunward half of the sky can hold coronagraph data: past 90 degrees of elongation
    // the line of sight is running away from the Sun. Empty sky, not clipped detail.
    if (ray.z >= 0.)
        discard;

    float observerDistance = projection[0].observerDistance;
    if (skyWarp.x > 0.) {
        // The field ends at the smaller of the loaded outer radius and the surface's own reach:
        // a Thomson sphere only reaches the observer, so with a field wider than that there is
        // simply no surface out there for the data to sit on. Normalising by the page position of
        // that radius rather than by 1 is what keeps this a one-to-one map, and with a field the
        // surface can describe it changes nothing (the page position of the outer radius is 1).
        float reach = surfaceModel <= 0. ? 1e30 : observerDistance / surfaceModel;
        float rMax = min(skyWarp.x, reach * 0.999);
        float eMax = surfaceElongation(rMax, observerDistance, surfaceModel);
        float uMax = warpUnitWith(rMax, skyWarp.x, skyWarp.y, skyWarp.z);
        float eDome = acos(clamp(-ray.z, -1., 1.));
        if (eMax <= 0. || eDome > eMax)
            discard; // past the edge of the composed field
        float r = unwarpRadiusWith(uMax * eDome / eMax, skyWarp.x, skyWarp.y, skyWarp.z);
        float eTrue = surfaceElongation(r, observerDistance, surfaceModel);
        vec2 perp = vec2(ray.x, ray.y);
        float len = length(perp);
        // The position angle about the Sun is untouched; only the elongation moves.
        ray = len < 1e-9 ? vec3(0., 0., -1.) : vec3(perp / len * sin(eTrue), -cos(eTrue));
    }

    vec2 helioprojective = vec2(atan(ray.x, -ray.z), asin(clamp(ray.y, -1., 1.)));

    // From here it is the HPC path exactly: helioprojective angles are what the samplers speak,
    // and this mode has only changed which part of the sky the screen is showing.
    vec4 color;
    bool diffMode = display.isDiff != NODIFFERENCE;
    vec2 hpcXY = helioprojectiveToHpcXY(helioprojective, observerDistance);
    vec2 texCoord;
    float enhancementFactor;
    clipHpcGeometry(hpcXY);
    texCoord = sampleHpcTexcoord(wcs[0], projection[0], helioprojective, hpcXY, wcs[0].deltaT, pv0, enhancementFactor);
    if (!diffMode) {
        color = getColor(texCoord, texCoord, enhancementFactor);
    } else {
        float diffObserverDistance = projection[1].observerDistance;
        vec2 diffHpcXY = helioprojectiveToHpcXY(helioprojective, diffObserverDistance);
        vec2 diffTexCoord;
        float diffEnhancementFactor;
        clipHpcGeometry(diffHpcXY);
        diffTexCoord = sampleHpcTexcoord(wcs[1], projection[1], helioprojective, diffHpcXY, wcs[1].deltaT, pv1, diffEnhancementFactor);
        color = getColor(texCoord, diffTexCoord, max(enhancementFactor, diffEnhancementFactor));
    }
    outColor = color;
}
