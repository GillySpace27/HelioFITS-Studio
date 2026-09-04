#!/usr/bin/env python3
"""The HDR expansion in solarCommon.frag, evaluated here with the same formulas so its properties
are pinned. E is the expansion (a multiple of SDR white in light) as a function of the DATA value
v that indexed the colour table, the gain G and the knee k. Run: python3 extra/test/hdr_curve_check.py"""
def expansion(mode, v, G, k):
    if mode == 0:
        return G
    t = min(max((min(max(v, 0), 1) - k) / (1 - k), 0), 1)
    return 1 + t * (G - 1) if mode == 1 else 1 + (G - 1) * t * t
G, k, eps = 2.0, 0.75, 1e-6
assert expansion(0, 0.1, G, k) == G and expansion(0, 1.0, G, k) == G, "linear expands everything equally"
for mode in (1, 2):
    assert expansion(mode, 0.3, G, k) == 1, "below the knee nothing changes"
    assert abs(expansion(mode, 1.0, G, k) - G) < eps, "the top of the data reaches the gain"
    assert abs(expansion(mode, k, G, k) - 1) < eps, "continuous at the knee"
    assert all(expansion(mode, a / 100, G, k) <= expansion(mode, (a + 1) / 100, G, k) + eps for a in range(100)), "monotonic"
d = 1e-5
assert (expansion(2, k + d, G, k) - 1) / d < 1e-3, "soft knee leaves the knee flat"
assert (expansion(1, k + d, G, k) - 1) / d > 1, "hard knee bends at once"
# roll to white: at the top of the data a saturated colour becomes neutral at the same luminance
lin = (0.05, 0.10, 0.90); Y = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
E = expansion(2, 1.0, G, k); w = (E - 1) / (G - 1)
out = tuple((1 - w) * c * E + w * Y * E for c in lin)
assert max(out) - min(out) < eps, f"fully rolled to white at the top, got {out}"
print("hdr_curve_check: PASS")
