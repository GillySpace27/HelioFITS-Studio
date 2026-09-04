#!/usr/bin/env python3
"""The three HDR mappings in solarCommon.frag, evaluated here with the same formulas, so their
properties are pinned: identity below the knee, the headroom exactly at white, no jump at the
knee, and for the soft knee no slope jump either. Run: python3 extra/test/hdr_curve_check.py"""
def mapped(mode, m, H, k):
    if mode == 0 or m <= k:
        return m * H if mode == 0 else m
    t = (m - k) / (1 - k)
    return k + t * (H - k) if mode == 1 else k + (1 - k) * t + (H - 1) * t * t
H, k, eps = 6.23, 0.5, 1e-6
assert abs(mapped(0, 1, H, k) - H) < eps and abs(mapped(0, 0.2, H, k) - 0.2 * H) < eps, "linear"
for mode in (1, 2):
    assert mapped(mode, 0.2, H, k) == 0.2, "below the knee is identity"
    assert abs(mapped(mode, 1, H, k) - H) < eps, "white reaches the headroom"
    assert abs(mapped(mode, k + 1e-7, H, k) - k) < 1e-5, "continuous at the knee"
d = 1e-5
slope_soft = (mapped(2, k + d, H, k) - mapped(2, k, H, k)) / d
assert abs(slope_soft - 1) < 1e-3, f"soft knee leaves the knee at slope 1, got {slope_soft}"
slope_hard = (mapped(1, k + d, H, k) - mapped(1, k, H, k)) / d
assert slope_hard > 5, "hard knee is meant to bend"
assert all(mapped(2, a / 100, H, k) <= mapped(2, (a + 1) / 100, H, k) + eps for a in range(100)), "monotonic"
print("hdr_curve_check: PASS")
