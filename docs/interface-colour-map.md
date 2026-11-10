# Where every sidebar and panel colour comes from

Written 2026-09-07 against the code, as preparation for a pass on colour and legibility. Nothing
here proposes a change; it is a map, so a change can be aimed rather than hunted for.

## The one switch

`DisplaySettings.UITheme` is `Dark` or `Light`, stored as `display.theme` and read once at startup
by `UIGlobals.setLaf()`, which installs `FlatDarkFlatIJTheme` or `FlatLightFlatIJTheme` and then the
JIDE extension on top. The radio pair is in Settings, under "Use theme"; `setUITheme` writes the
property and sets the field, and **nothing re-runs `setLaf`**, so the change lands on the next
launch. There is no live theme switch today.

Almost every colour in the application is therefore FlatLaf's, reached through `UIManager`. What
follows is the short list of places that do something of their own.

## The three derived colours

`UIGlobals` captures, right after the theme is installed:

| name | source |
|---|---|
| `foreColor` | `UIManager.getColor("Label.foreground")` |
| `backColor` | `UIManager.getColor("Label.background")` |
| `midColor` | the arithmetic mean of the two, channel by channel |

These are the only application-wide colour constants. The timeline gets its own dozen
(`TL_*`), set in the same method by a switch on the theme: the dark branch derives everything from
`foreColor`/`backColor` with `brighter()`/`darker()`, while the light branch hard-codes greys and
two RGB literals. That asymmetry is the largest single inconsistency in the file.

## Section headers

`CollapsiblePane` is the header plus its managed component. It stores its own expanded state under
`ui.section.<title with underscores>`, so a section opens the way it was last left, and a `child`
flag picks `uiFontSmall` over `uiFontSmallBold` for nested sections.

`CollapsiblePaneButton` paints the header bar itself: two `GradientPaint` halves between `bright`
and `dark`, both derived from `backColor` by private `brighter(0.85)` and `darker(0.9)` helpers, and
mirrored top-to-bottom depending on whether the section is open. It is the one piece of custom
chrome in the sidebar, it is a static field so it is fixed at class-load time, and it is where a
header would get more or less contrast against the panel behind it.

### Easier to find panels

`PanelVisibility` (gui/component) holds the Visibility options of the Theme dialog, under the settings
`ui.visibility.enabled` (master) and `ui.visibility.headerBand|headerRule|headerGap|headerText|stripe|numbers|hover`, all on unless stored as
`false`. (The four header options used to be one switch, `ui.visibility.headers`; an option with nothing stored follows it, so a stored `false` keeps all four off.) They are not theme tokens. With the master off every value is the theme's own. On, from the
current theme's tokens: the `HeaderFill` band is mixed toward `Foreground` until it reaches 4.5:1 on
`Background`, stopping where the title (`HeaderText`, brightened or darkened if needed) can no longer be
held at 4.5:1 on it; the nested band is stepped down from it as `Theme.derive` does from the stated one
and held to 3:1; the rule is 2px and the gap under a section 9px; the stripe is `Accent` held to 3:1 on the
band (`Theme.legibleOn`), inset 2px so band colour shows beside it; the hover fill mixes the band toward
`Foreground` where the title still reads. Numbers are display text only: `CollapsiblePane.getTitle()`,
the section's preference key and every control id are unchanged. `PanelVisibilityCheck` covers all of this.

## The layer rows

`CellRenderer` paints all six columns of every layer table. Its colour rules:

- Selection wins: `table.getSelectionBackground()`.
- Otherwise an image layer gets a **format wash**: green for FITS, blue for the JP2 family, amber
  for PNG and JPEG, mixed toward the table background at `TINT = 0.12`. Non-image layers get no
  wash, so the overlay and camera lists are plain.
- Everything else is the table's own background and foreground.

`TINT` is the knob for how loud that wash is. The three hues are RGB literals in `tintFor`.

## Borders and separators

A one-pixel `MatteBorder` in `getBackground().brighter()` is the house separator. It appears at the
top of the layer list, the space-object list, the FOV tree, the timeline list and the status panel,
and at the bottom of the toolbar. The layer list's drag handle uses the same colour as a fill.
Because it is `Color.brighter()` on the panel background rather than a named token, it is one
multiplication away from invisible on a light theme.

## The floating palettes

`Palette` builds the three always-on-top windows: Fourier filter, Projection and HDR. Its content
panel takes a `LineBorder` in `getBackground().brighter()` plus padding; the header is a bold label
with transparent backgrounds and two JIDE buttons. It carries no colours of its own beyond that
border, so it inherits whatever the theme gives a dialog, and its legibility over a bright canvas
is a question of that border and the window's own opacity, neither of which is set here.

## Remaining hard-coded colours

- `MainFrame`: the render placeholder is `Color.BLACK` before the canvas attaches.
- `ToolBar`: a swatch icon outlined in `Color.DARK_GRAY`.
- `SequencePanel` and `GridLayerOptions`: literals for plot curves and grid pickers, which are data
  colours rather than chrome.
- `UIGlobals`: the light-theme timeline literals noted above.

## What is missing, if the goal is a coherent palette

There is no application colour token beyond the three in `UIGlobals`, so every accent is either
FlatLaf's or a literal at its point of use, and nothing enumerates them. There is also no contrast
check anywhere: the format wash, the header gradient and the matte separators are all defined as
small perturbations of the background, which is exactly the family of choices that survives one
theme and disappears in the other.
