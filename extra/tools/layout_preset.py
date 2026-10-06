#!/usr/bin/env python3
"""Write the layout keys of a user.properties as resources/settings/layout-defaults.properties.

Usage: python3 extra/tools/layout_preset.py ~/HFStudio/Settings/user.properties

Copies only the keys Settings.isLayoutKey accepts, verbatim, sorted. Never prints values.
"""
import pathlib
import re
import sys

EXACT = {"ui.rightSidebarOrder", "ui.sidebarWidth", "ui.rightSidebarWidth", "ui.sidebarCollapsed",
         "ui.rightSidebarCollapsed", "ui.panelsLocked", "display.toolbar", "display.toolbar.visible",
         "display.statusbar.visible", "display.plugins"}
PREFIXES = ("ui.toolbar.", "ui.palette.", "ui.section.")
OUT = pathlib.Path(__file__).resolve().parents[2] / "resources/settings/layout-defaults.properties"


def is_layout_key(key):
    # As Settings.isLayoutKey: a section named after a layer follows the open data, not the layout.
    if key.startswith("ui.section.") and key != "ui.section.Layer_Options" and (
            key.endswith("_Layer_Options") or re.fullmatch(r"ui\.section\.\d+_Layers_Selected", key)):
        return False
    return key in EXACT or key.startswith(PREFIXES)


def main(argv):
    if len(argv) != 2:
        sys.exit(__doc__)
    lines = pathlib.Path(argv[1]).read_text(encoding="latin-1").splitlines()
    keep = []
    for line in lines:
        if not line or line[0] in "#!" or "=" not in line or line.rstrip().endswith("\\"):
            continue
        key = line.split("=", 1)[0].strip()
        if is_layout_key(key):
            keep.append(line)
    header = [l for l in OUT.read_text(encoding="latin-1").splitlines() if l.startswith("#")]
    OUT.write_text("\n".join(header + sorted(keep)) + "\n", encoding="latin-1")
    print(f"{len(keep)} layout keys written to {OUT}")


if __name__ == "__main__":
    main(sys.argv)
