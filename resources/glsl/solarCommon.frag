#version 300 es

precision highp float;

#define NODIFFERENCE 0.
#define PI 3.1415926535897932384626433832795
#define HALFPI (PI / 2.)
#define TWOPI  (2. * PI)

#define CLIP_SCALE_NARROW 1. / (2. * 32.)
#define CLIP_SCALE_WIDE   1. / (2. * 50. * 215.09151684811678)

#define BOOST 1. / (0.2 * 2.)

const float WCS_PROJECTION_TAN = 0.;
const float WCS_PROJECTION_ARC = 1.;
const float WCS_PROJECTION_AZP = 2.;
const float WCS_PROJECTION_ZPN = 3.;
const float WCS_PROJECTION_CAR = 4.;
const float WCS_PROJECTION_CEA = 5.;

out vec4 outColor;
in vec2 normalizedScreenpos;

struct WCS {
    vec4 cameraDiff; // not strictly WCS
    vec4 rect;
    vec4 planeToImage; // row-major 2x2 matrix
    vec2 crval;
    float zpnUpperEta;
    float deltaT; // not strictly WCS
};

layout(std140) uniform WCSBlock {
    WCS wcs[2];
};

struct ProjectionParams {
    float projectionCode;
    float planeUnitsPerRadian;
    float observerDistance;
    float padding0;
    vec4 sourceViewQuat;
    vec4 displayMapQuat;
};

layout(std140) uniform ProjectionBlock {
    ProjectionParams projection[2];
};

layout(std140) uniform ScreenBlock {
    mat4 inverseMVP;
    float iaspect;
    float xStart;
    float xStop;
    float yStart;
    float yStop;
    float lambda;
    float limb;
} screen;

layout(std140) uniform DisplayBlock {
    vec4 color;
    vec3 sharpen;
    float isDiff;
    vec3 sector;
    float enhanced;
    vec3 cutOff;
    float calculateDepth;
    vec2 brightness;
    vec2 radii;
    vec2 slit;
    float upsilonLow;
    float upsilonHigh;
    // Non-zero for index-coded categorical images, whose pixel value selects a LUT entry rather
    // than a position on a ramp. The Java-side put() sequence (GLSLSolarShader) must mirror this
    // member order byte-for-byte, and the buffer capacity must be >= this std140 block size
    // rounded up to a multiple of 16 (the trailing 12 bytes after this scalar are that rounding,
    // not per-member padding -- a bare float has 4-byte base alignment, not 16).
    float indexed;
    // Non-zero while capturing to a high-bit-depth destination. Dither exists to hide 8-bit
    // banding; writing it into a 16-bit file would just be recording the noise. Occupies one of
    // the three trailing floats that were std140 rounding, so the block size is unchanged.
    float skipDither;
    // Non-zero to paint clipped pixels in flag colours instead of their LUT colour.
    float showClipping;
    // Non-zero while the export reads a layer as numbers: the value that would index the colour
    // table is written out as is, grey, alpha 1. Takes the last padding float, so the block
    // size is unchanged.
    float rawOutput;
    // Multiplies an image layer's RGB after the colour table, on the EDR canvas: 1 means
    // interface white, the screen's headroom means its peak. Alpha is never scaled. Starts a new
    // std140 16-byte row (the previous rounding floats were all taken), so the Java buffer grows
    // by four floats.
    float hdrGain;
    // How the gain is applied. The knee modes are driven by the DATA value that indexed the
    // colour table, not by how bright the table's colour happens to be: a table that lives
    // near white (PUNCH) would otherwise put most of its field over white, and a saturated one
    // (LASCO blue) would go neon. Only the brightest data gets the headroom.
    //   0  linear:    every pixel's colour scaled by the gain;
    //   1  hard knee: unchanged up to hdrKnee of the data range, then expansion rising on a
    //                 straight line to the gain at the top;
    //   2  soft knee: as 1 with a curve that leaves the knee flat, and the colour rolling
    //                 toward white as it climbs, which is what bright light looks like;
    //   3  beyond range: the display range is the SDR picture, untouched; data ABOVE its top
    //                 (the texture keeps them, linear in physical units past 1) shine over
    //                 white in proportion, rolling to white. Nothing inside the range moves,
    //                 and what used to be a flat plateau is graded again;
    //   4  uniform:   CIE lightness runs in a straight line from black to the display's peak
    //                 over the whole range, in place of the table's own lightness. A gain of 2
    //                 in light is only ~30 L*, so the linear modes leave the top of a table
    //                 within a few percent of white; this is the mode where the legend reads
    //                 as one even ramp.
    float hdrMode;
    float hdrKnee;
} display;

uniform sampler2D image;
uniform sampler2D diffImage;
uniform sampler2D lut;
uniform sampler2D mask;

uniform float pv0[6]; // kept as plain uniforms for simple indexed access
uniform float pv1[6];
uniform vec3 latiGrid[2];

#define BLUR_TAP_COUNT (3 * 3)
// float[] bc = { 0.06136, 0.24477, 0.38774, 0.24477, 0.06136 }
// https://www.rastergrid.com/blog/2010/09/efficient-gaussian-blur-with-linear-sampling/
const float[] bc = float[](.30613, .38774, .30613);
const float[] blurKernel = float[](
    bc[0] * bc[0], bc[0] * bc[1], bc[0] * bc[2],
    bc[1] * bc[0], bc[1] * bc[1], bc[1] * bc[2],
    bc[2] * bc[0], bc[2] * bc[1], bc[2] * bc[2]
);

const float[] bo = float[](-1.2004377, 0., 1.2004377);
const vec2[] blurOffset = vec2[](
    vec2(bo[0], bo[0]), vec2(bo[1], bo[0]), vec2(bo[2], bo[0]),
    vec2(bo[0], bo[1]), vec2(bo[1], bo[1]), vec2(bo[2], bo[1]),
    vec2(bo[0], bo[2]), vec2(bo[1], bo[2]), vec2(bo[2], bo[2])
);

// https://shader-tutorial.dev/advanced/color-banding-dithering/
const float NOISE_GRANULARITY = 1. / 255.;
const vec2 nvec = vec2(12.9898, 78.233);

float dither(const vec2 coord) {
    float random = fract(sin(dot(coord, nvec)) * 43758.5453);
    return mix(-NOISE_GRANULARITY, NOISE_GRANULARITY, random);
}

float fetch(const sampler2D tex, const vec2 coord, const vec2 bright) {
    return texture(tex, coord).r * bright.y + bright.x;
}

vec4 getColor(const vec2 texcoord, const vec2 difftexcoord, const float factor) {
    if (texture(mask, texcoord).r == 0.)
        discard;

    vec2 brightness = display.brightness;
    if (display.enhanced != 0. && factor != 1.)
        brightness.y *= pow(factor, display.enhanced);

    float value;
    bool diffMode = display.isDiff != NODIFFERENCE;
    if (!diffMode) {
        value = fetch(image, texcoord, brightness);
    } else {
        value = fetch(image, texcoord, brightness) - fetch(diffImage, difftexcoord, brightness);
        value = value * BOOST + 0.5;
    }

    vec2 sharpenStep = display.sharpen.xy;
    float sharpenMix = display.sharpen.z;
    if (sharpenMix != 0.) {
        float blurredValue = 0.;
        if (!diffMode) {
            for (int i = 0; i < BLUR_TAP_COUNT; i++) {
                vec2 offset = blurOffset[i] * sharpenStep;
                blurredValue += fetch(image, texcoord + offset, brightness) * blurKernel[i];
            }
        } else {
            for (int i = 0; i < BLUR_TAP_COUNT; i++) {
                vec2 offset = blurOffset[i] * sharpenStep;
                blurredValue += (fetch(image, texcoord + offset, brightness) - fetch(diffImage, difftexcoord + offset, brightness)) * blurKernel[i];
            }
            blurredValue = blurredValue * BOOST + 0.5;
        }
        value = mix(value, blurredValue, sharpenMix);
    }

    if (display.upsilonLow != 1. || display.upsilonHigh != 1.) {
        // Two-sided gamma about the median (Gilly & DeForest Eq. 2): upsilonLow and
        // upsilonHigh independently set the curvature below and above I = 0.5
        value = clamp(value, 0., 1.);
        value = value < .5 ? .5 * pow(2. * value, display.upsilonLow) : 1. - .5 * pow(2. - 2. * value, display.upsilonHigh);
    }

    // A data export wants the number, not the colour table's 8-bit rendering of it (LINEAR
    // sampling of 256 entries at texel-edge coordinates is an affine map with a 0.4 percent
    // gain, which is fine for a picture and a defect in a data channel).
    if (display.rawOutput != 0.)
        return vec4(value, value, value, 1.);

    // Clipping flags, tested BEFORE the dither so a +/-1/255 nudge is never reported as
    // clipping. This is the only place the transfer function's range is knowable: fetch()
    // applies Levels and the response factor without clamping, and the LUT texture is
    // CLAMP_TO_EDGE, so everything at or past the ends silently renders as the end colour.
    // Magenta and green because no solar colour table contains either.
    // Skipped for categorical layers, where the value is an index and "range" means nothing.
    if (display.showClipping != 0. && display.indexed == 0.) {
        if (value >= 1.)
            return vec4(1., 0., 1., 1.) * display.color;
        if (value <= 0.)
            return vec4(0., 1., 0., 1.) * display.color;
    }

    // Dither breaks up banding in continuous ramps, but on a categorical LUT a +/-1/255 nudge
    // lands on a neighbouring legend entry, turning flat regions into salt-and-pepper noise.
    if (display.indexed == 0. && display.skipDither == 0.)
        value += dither(texcoord);

    vec4 colour = texture(lut, vec2(value, 0.5)) * display.color;
    if (display.hdrGain == 1. || display.indexed != 0.)
        return colour;
    // The gain is a multiple of SDR white in LIGHT, so it is applied to linear values: the colour
    // table is sRGB-encoded, and multiplying the encoded value by 6 would be 6^2.2 in light, which
    // clips the whole top of the image at the panel's peak. Decode, scale, re-encode with the
    // curve extended past 1.0 (monotonic there), which the Metal presenter inverts exactly.
    vec3 lin = mix(colour.rgb / 12.92, pow((colour.rgb + 0.055) / 1.055, vec3(2.4)), step(0.04045, colour.rgb));
    float G = display.hdrGain, k = display.hdrKnee;
    float E = G; // expansion, a multiple of SDR white in light
    if (display.hdrMode == 3.)
        E = clamp(value, 1., G);
    else if (display.hdrMode == 4.) {
        // Uniform: the headroom spent in LIGHTNESS rather than light. Doubling the luminance is
        // only about 30 L* more, so a linear gain of 2 puts the whole top half of a table within
        // a few percent of white and the legend reads as a plateau. Here CIE L* runs straight from
        // black to the brightest the display offers (116 G^(1/3) - 16), whatever the table's own
        // lightness did: the colour keeps its hue and is scaled to the luminance that lightness
        // calls for. A table that is already lightness-linear (PUNCH) keeps its look below white.
        float Lt = clamp(value, 0., 1.) * (116. * pow(G, 1. / 3.) - 16.);
        float Yt = Lt > 8. ? pow((Lt + 16.) / 116., 3.) : Lt / 903.3;
        float Y0 = dot(lin, vec3(0.2126, 0.7152, 0.0722));
        E = Y0 > 1e-6 ? min(Yt / Y0, G) : 1.;
    }
    else if (display.hdrMode != 0.) {
        float t = clamp((clamp(value, 0., 1.) - k) / (1. - k), 0., 1.);
        E = display.hdrMode == 1. ? 1. + t * (G - 1.) : 1. + (G - 1.) * t * t;
    }
    lin *= E;
    if (display.hdrMode >= 2.) {
        // Roll to white: the further over SDR white, the closer to a neutral of the same
        // luminance. Without this a saturated table colour at 4x is neon, not bright.
        float Y = dot(lin, vec3(0.2126, 0.7152, 0.0722));
        lin = mix(lin, vec3(Y), (E - 1.) / max(G - 1., 1e-4));
    }
    vec3 enc = mix(lin * 12.92, 1.055 * pow(lin, vec3(1. / 2.4)) - 0.055, step(0.0031308, lin));
    return vec4(enc, colour.a);
}

void clamp_texture(const vec2 texcoord) {
    if (texcoord.x < 0. || texcoord.y < 0. || texcoord.x > 1. || texcoord.y > 1.)
        discard;
}

void clamp_coord(const vec2 coord) {
    if (coord.x < display.slit.x || coord.y < 0. || coord.x > display.slit.y || coord.y > 1.)
        discard;
}

void clamp_value(const float value, const float low, const float high) {
    if (value < low || value > high)
        discard;
}

// Convert normalized screen coordinates to the view-aligned plane in scene units.
// The projection is orthographic, so xy is independent of clip-space z and needs no perspective divide.
vec2 getViewPosition(void) {
    return (screen.inverseMVP * vec4(normalizedScreenpos, -1., 1.)).xy;
}

// Map the centered view plane to the [0, 1] map domain: remove the viewport's
// horizontal aspect scaling, move the origin to the lower-left, and discard outside it.
vec2 getNormalizedMapPos(void) {
    vec2 pos = getViewPosition();
    pos = vec2(screen.iaspect * pos.x, pos.y) + .5;
    clamp_coord(pos);
    return pos;
}

// Convert a normalized warp radius back to radial distance in solar radii.
// The disk is linear; only distances beyond the limb use Box-Cox scaling.
float unwarpRadius(float normalizedRadius) {
    float outerRadius = screen.yStop;
    float limbPosition = screen.limb > 0. ? screen.limb : 1. / outerRadius;
    if (outerRadius <= 1. || normalizedRadius <= limbPosition)
        return normalizedRadius / limbPosition;

    float u = (normalizedRadius - limbPosition) / (1. - limbPosition);
    float lambda = screen.lambda;
    return lambda == 0.
            ? pow(outerRadius, u)
            : pow(1. + u * (pow(outerRadius, lambda) - 1.), 1. / lambda);
}

// Twin of display/SurfaceModel.java. Where a line of sight is taken to have originated:
// the plane of sky (r = D tan e, z = 0), the Thomson sphere of 90-degree scattering
// (r = D sin e, z = r^2 / D), or the celestial sphere centred on the observer
// (r = 2D sin(e/2), z = r^2 / 2D). The model value is the family parameter k = D / L for the
// sphere of diameter L through the Sun. A placement model, not a measured depth. Keep the two in
// step -- the mesh is built in Java and sampled here, so a divergence shows up as imagery
// sliding off its own geometry.
#define SURFACE_PLANE_OF_SKY 0.
#define SURFACE_THOMSON_SPHERE 1.
#define SURFACE_CELESTIAL_SPHERE .5
// Both models degenerate at 90 degrees; clamp rather than divide. Matches
// SurfaceModel.MAX_ELONGATION.
#define MAX_ELONGATION 1.5533431

float surfaceHeliocentricRadius(const float elongation, const float observerDistance, const float model) {
    float e = clamp(elongation, 0., MAX_ELONGATION);
    if (model == SURFACE_THOMSON_SPHERE)
        return observerDistance * sin(e);
    if (model == SURFACE_CELESTIAL_SPHERE)
        return observerDistance * 2. * sin(.5 * e);
    return observerDistance * tan(e);
}

float surfaceDepth(const float heliocentricRadius, const float observerDistance, const float model) {
    if (model <= 0. || observerDistance <= 0.)
        return 0.;
    float reach = observerDistance / model; // the sphere's diameter L = D / k
    float r = min(heliocentricRadius, reach);
    return r * r / reach;
}

float surfaceElongation(const float heliocentricRadius, const float observerDistance, const float model) {
    if (observerDistance <= 0.)
        return 0.;
    float ratio = heliocentricRadius / observerDistance;
    if (model == SURFACE_THOMSON_SPHERE)
        return asin(clamp(ratio, -1., 1.));
    if (model == SURFACE_CELESTIAL_SPHERE)
        return 2. * asin(clamp(.5 * ratio, -1., 1.));
    return atan(ratio);
}

vec3 rotate_vector_inverse(const vec4 quat, const vec3 vec) {
    return vec + 2. * cross(cross(vec, quat.xyz) + quat.w * vec, quat.xyz);
}

vec3 rotate_vector(const vec4 quat, const vec3 vec) {
    return vec + 2. * cross(quat.xyz, cross(quat.xyz, vec) + quat.w * vec);
}

vec2 transform_plane_to_image(const vec4 transform, const vec2 vec) {
    return vec2(
        transform.x * vec.x + transform.y * vec.y,
        transform.z * vec.x + transform.w * vec.y);
}

// Differential solar rotation.
float differentialRotation(const float dt, const float theta) {
    float sinLat2 = sin(theta);
    sinLat2 *= sinLat2;
    // Snodgrass, Table 1 Magnetic - http://articles.adsabs.harvard.edu/pdf/1990ApJ...351..309S
    return dt * (0.01367 - 0.339 * sinLat2 - 0.485 * sinLat2 * sinLat2); // 2.879 urad/s - 14.1844 deg/86400s (not fully right: 1st SI, 2nd TDB)
}

vec3 differential(const float dt, const vec3 v) {
    float phi = atan(v.x, v.z);
    float theta = asin(v.y);
    phi -= differentialRotation(dt, theta); // difference from rigid rotation
    return vec3(cos(theta) * sin(phi), v.y, cos(theta) * cos(phi));
}

// Observer-centred helioprojective geometry.
vec2 worldToHelioprojective(const vec3 world, const float observerDistance) {
    float zeta = observerDistance - world.z;
    return vec2(
        atan(world.x, zeta),
        atan(world.y, sqrt(world.x * world.x + zeta * zeta)));
}

vec3 observerPosition(const float observerDistance) {
    return vec3(0., 0., observerDistance);
}

vec3 helioprojectiveToObserverRay(const vec2 helioprojective) {
    float phi = helioprojective.x;
    float theta = helioprojective.y;
    float cosPhi = cos(phi);
    float cosTheta = cos(theta);
    float raySign = cosPhi * cosTheta < 0. ? -1. : 1.;
    return vec3(raySign * sin(phi) * cosTheta, raySign * sin(theta), -raySign * cosPhi * cosTheta);
}

vec3 helioprojectiveToHpcPlanePoint(const vec2 helioprojective, const float observerDistance) {
    vec3 ray = helioprojectiveToObserverRay(helioprojective);
    if (ray.z >= 0.)
        discard;
    return observerPosition(observerDistance) - observerDistance * ray / ray.z;
}

// Native zenithal coordinates for TAN/AZP/ZPN forward projection.
void nativeZenithalCoordinates(
    const vec2 helioprojective,
    const vec2 crval,
    const float planeUnitsPerRad,
    out float nativeX,
    out float nativeY,
    out float cosNativeDistance
) {
    float phi = helioprojective.x;
    float theta = helioprojective.y;
    vec2 referenceAngles = crval / planeUnitsPerRad;
    float phi0 = referenceAngles.x;
    float theta0 = referenceAngles.y;

    float sinLat = sin(theta);
    float cosLat = cos(theta);
    float sinLat0 = sin(theta0);
    float cosLat0 = cos(theta0);
    float deltaLon = phi - phi0;
    float sinDeltaLon = sin(deltaLon);
    float cosDeltaLon = cos(deltaLon);

    nativeX = cosLat * sinDeltaLon;
    nativeY = cosLat0 * sinLat - sinLat0 * cosLat * cosDeltaLon;
    cosNativeDistance = sinLat0 * sinLat + cosLat0 * cosLat * cosDeltaLon;
}

vec2 projectTanToWcsPlane(const vec2 helioprojective, const vec2 crval, const float planeUnitsPerRad) {
    float nativeX;
    float nativeY;
    float cosNativeDistance;
    nativeZenithalCoordinates(helioprojective, crval, planeUnitsPerRad, nativeX, nativeY, cosNativeDistance);
    if (cosNativeDistance <= 0.)
        discard;

    return planeUnitsPerRad * vec2(
        nativeX / cosNativeDistance,
        nativeY / cosNativeDistance);
}

vec2 projectArcToWcsPlane(const vec2 helioprojective, const vec2 crval, const float planeUnitsPerRad) {
    float nativeX;
    float nativeY;
    float cosNativeDistance;
    nativeZenithalCoordinates(helioprojective, crval, planeUnitsPerRad, nativeX, nativeY, cosNativeDistance);
    float nativeRadius = length(vec2(nativeX, nativeY));
    if (nativeRadius == 0.)
        return vec2(0.);

    float nativeDistance = atan(nativeRadius, cosNativeDistance);
    return planeUnitsPerRad * vec2(
        nativeDistance * nativeX / nativeRadius,
        nativeDistance * nativeY / nativeRadius);
}

vec2 projectAzpToWcsPlane(const vec2 helioprojective, const vec2 crval, const float planeUnitsPerRad, const float[6] PV) {
    float mu = PV[1];
    float gamma = radians(PV[2]);

    float nativeX;
    float nativeY;
    float cosNativeDistance;
    nativeZenithalCoordinates(helioprojective, crval, planeUnitsPerRad, nativeX, nativeY, cosNativeDistance);
    float nativeRadius = length(vec2(nativeX, nativeY));
    if (nativeRadius == 0.)
        return vec2(0.);

    // For the non-slanted AZP case, mu > 1 folds back once dR/dtheta changes sign.
    // Keep only the primary forward branch.
    if (gamma == 0. && mu > 1. && mu * cosNativeDistance + 1. <= 0.)
        discard;

    float denom = mu + cosNativeDistance - nativeY * tan(gamma);
    if (denom <= 0.)
        discard;

    float radial = (mu + 1.) * nativeRadius / denom;
    return planeUnitsPerRad * vec2(
        radial * nativeX / nativeRadius,
        radial * nativeY / (nativeRadius * cos(gamma)));
}

float zpnRadial(const float eta, const float[6] PV) {
    float radial = PV[5];
    for (int i = 4; i >= 0; --i)
        radial = radial * eta + PV[i];
    return radial;
}

vec2 projectZpnToWcsPlane(const vec2 helioprojective, const WCS wcs, const float planeUnitsPerRad, const float[6] PV) {
    float nativeX;
    float nativeY;
    float cosNativeDistance;
    nativeZenithalCoordinates(helioprojective, wcs.crval, planeUnitsPerRad, nativeX, nativeY, cosNativeDistance);
    float nativeRadius = length(vec2(nativeX, nativeY));
    if (nativeRadius == 0.)
        return vec2(0.);

    float nativeDistance = atan(nativeRadius, cosNativeDistance);
    if (nativeDistance > wcs.zpnUpperEta)
        discard;

    float radial = zpnRadial(nativeDistance, PV);
    if (radial < 0.)
        discard;

    return planeUnitsPerRad * vec2(
        radial * nativeX / nativeRadius,
        radial * nativeY / nativeRadius);
}

float wrapDeltaLongitude(float lon, float lon0) {
    return mod(lon - lon0 + PI, TWOPI) - PI;
}

// Surface-map forward projections used by Latitudinal and Orthographic.
vec2 projectCarToWcsPlane(const vec3 world, const vec2 crval, const float planeUnitsPerRad) {
    // CAR is a direct surface lon/lat map, not observer-image geometry.
    float lon = atan(world.x, world.z);
    float lat = asin(clamp(world.y / length(world), -1., 1.));
    vec2 referenceAngles = crval / planeUnitsPerRad;
    return vec2(
        planeUnitsPerRad * wrapDeltaLongitude(lon, referenceAngles.x),
        planeUnitsPerRad * (lat - referenceAngles.y));
}

vec2 projectCeaToWcsPlane(const vec3 world, const vec2 crval, const float planeUnitsPerRad, const float[6] PV) {
    // CEA is a direct surface lon/lat map with equal-area latitude scaling.
    float lon = atan(world.x, world.z);
    float lat = asin(clamp(world.y / length(world), -1., 1.));
    float lambda = max(abs(PV[1]), 1e-12);
    vec2 referenceCoord = crval / planeUnitsPerRad;
    return vec2(
        planeUnitsPerRad * wrapDeltaLongitude(lon, referenceCoord.x),
        planeUnitsPerRad * (sin(lat) / lambda - referenceCoord.y));
}

// Projection-space to texture-space mapping.
vec2 projectHelioprojectiveToWcsPlane(const vec2 helioprojective, const WCS wcs, const ProjectionParams projection, const float[6] PV) {
    if (projection.projectionCode == WCS_PROJECTION_TAN)
        return projectTanToWcsPlane(helioprojective, wcs.crval, projection.planeUnitsPerRadian);
    if (projection.projectionCode == WCS_PROJECTION_ARC)
        return projectArcToWcsPlane(helioprojective, wcs.crval, projection.planeUnitsPerRadian);
    if (projection.projectionCode == WCS_PROJECTION_AZP)
        return projectAzpToWcsPlane(helioprojective, wcs.crval, projection.planeUnitsPerRadian, PV);
    if (projection.projectionCode == WCS_PROJECTION_ZPN)
        return projectZpnToWcsPlane(helioprojective, wcs, projection.planeUnitsPerRadian, PV);

    return projectTanToWcsPlane(helioprojective, wcs.crval, projection.planeUnitsPerRadian);
}

vec2 wcsPlaneToTexcoord(const vec2 plane, const WCS wcs) {
    vec2 centered = transform_plane_to_image(wcs.planeToImage, plane);
    vec4 rect = wcs.rect;
    vec2 texcoord = rect.zw * vec2(centered.x - rect.x, -centered.y - rect.y);
    clamp_coord(texcoord);
    return texcoord;
}

vec2 wcsPlaneToWrappedXTexcoord(const vec2 plane, const WCS wcs) {
    vec2 centered = transform_plane_to_image(wcs.planeToImage, plane);
    vec4 rect = wcs.rect;
    vec2 texcoord = rect.zw * vec2(centered.x - rect.x, -centered.y - rect.y);
    texcoord.x = fract(texcoord.x);
    clamp_coord(texcoord);
    return texcoord;
}

vec2 normalizedMapToHelioprojective(const vec2 mapPos) {
    return vec2(
        radians(screen.xStart + mapPos.x * (screen.xStop - screen.xStart)),
        radians(screen.yStart + mapPos.y * (screen.yStop - screen.yStart)));
}

bool helioprojectiveToWorld(const vec2 helioprojective, const float observerDistance, out vec3 world) {
    vec3 ray = helioprojectiveToObserverRay(helioprojective);
    float b = observerDistance * ray.z;
    float c = observerDistance * observerDistance - 1.;
    vec3 observer = observerPosition(observerDistance);
    float discriminant = b * b - c;
    if (discriminant < 0.) {
        world = vec3(0.);
        return false;
    }

    float root = sqrt(discriminant);
    float tNear = -b - root;
    float tFar = -b + root;
    float t = tNear > 0. ? tNear : tFar;
    if (t <= 0.) {
        world = vec3(0.);
        return false;
    }

    world = observer + t * ray;
    return true;
}

vec2 helioprojectiveToHpcXY(const vec2 helioprojective, const float observerDistance) {
    return helioprojectiveToHpcPlanePoint(helioprojective, observerDistance).xy;
}

vec2 hpcXYToHelioprojective(const vec2 hpcXY, const float observerDistance) {
    return worldToHelioprojective(vec3(hpcXY, 0.), observerDistance);
}

float hpcEnhancementFactor(const vec2 hpcXY) {
    return max(1., length(hpcXY));
}

void clipHpcGeometry(const vec2 hpcXY) {
    if (display.sector.z != 0.) {
        float theta = atan(hpcXY.y, hpcXY.x);
        if (theta < display.sector.x || theta > display.sector.y)
            discard;
    }

    float radial2 = dot(hpcXY, hpcXY);
    float minRadius2 = display.radii.x * display.radii.x;
    float maxRadius2 = display.radii.y * display.radii.y;
    if (radial2 > maxRadius2 || radial2 < minRadius2)
        discard;

    if (display.cutOff.z >= 0.) {
        float flatDist = abs(dot(hpcXY, display.cutOff.xy));
        vec2 cutOffAlt = vec2(-display.cutOff.y, display.cutOff.x);
        float flatDistAlt = abs(dot(hpcXY, cutOffAlt));
        if (flatDist > display.cutOff.z || flatDistAlt > display.cutOff.z)
            discard;
    }
}

bool isSurfaceMapProjection(const ProjectionParams projection) {
    return projection.projectionCode == WCS_PROJECTION_CAR
        || projection.projectionCode == WCS_PROJECTION_CEA;
}

/**
 * Sample a CAR/CEA surface map (the indexed Carrington synoptic map, among others) along a
 * helioprojective line of sight.
 *
 * A surface map is a map OF THE SPHERE: every texel is a longitude/latitude, so the only way
 * to sample it is to intersect the line of sight with the solar surface and ask where that
 * point lands on the map. That is why this returns false off the limb rather than falling
 * back to a plane -- a sight line that misses the Sun has no surface point, and the caller
 * must discard rather than invent one. It is also why the projection-generic
 * projectHelioprojectiveToWcsPlane() cannot serve here: it has no CAR/CEA branch and silently
 * treats them as TAN, which is what left these maps blank in every non-ortho projection.
 *
 * The frame correction mirrors solarOrtho.frag: sight lines are built in the observer frame,
 * while the map is glued to the Sun, so sourceViewQuat (which ImageLayer fills with the view
 * rotation for surface maps specifically) undoes the view. Wrapped X, because a synoptic map
 * is periodic in longitude and the seam would otherwise clip.
 */
bool sampleSurfaceMapTexcoord(const vec2 helioprojective, const WCS wcs, const ProjectionParams projection, const float[6] PV, out vec2 texCoord) {
    vec3 world;
    if (!helioprojectiveToWorld(helioprojective, projection.observerDistance, world)) {
        texCoord = vec2(0.);
        return false;
    }
    vec3 surface = rotate_vector_inverse(projection.sourceViewQuat, world);
    vec2 plane = projection.projectionCode == WCS_PROJECTION_CEA
            ? projectCeaToWcsPlane(surface, wcs.crval, projection.planeUnitsPerRadian, PV)
            : projectCarToWcsPlane(surface, wcs.crval, projection.planeUnitsPerRadian);
    texCoord = wcsPlaneToWrappedXTexcoord(plane, wcs);
    return true;
}

vec2 sampleHpcTexcoord(const WCS wcs, const ProjectionParams projection, vec2 helioprojective, const vec2 hpcXY, const float dt, const float[6] PV, out float enhancementFactor) {
    enhancementFactor = 1.;
    float observerDistance = projection.observerDistance;

    vec3 world;
    if (helioprojectiveToWorld(helioprojective, observerDistance, world)) {
        if (dt != 0.) {
            vec3 rotatedWorld = differential(dt, world);
            helioprojective = worldToHelioprojective(rotatedWorld, observerDistance);
        }
    } else {
        enhancementFactor = hpcEnhancementFactor(hpcXY);
    }

    vec2 plane = projectHelioprojectiveToWcsPlane(helioprojective, wcs, projection, PV);
    return wcsPlaneToTexcoord(plane, wcs);
}
