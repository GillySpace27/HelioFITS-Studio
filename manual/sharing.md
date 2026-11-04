# Sessions, templates and sharing

## Sessions

{menuselection}`File --> Save Session` writes the whole scene (its layers and settings) to a `.jhv`
file that opens it again later, with {menuselection}`File --> Open Session...` or by dropping it on
the window. {menuselection}`File --> Set Current Session as Default` opens this scene at every start,
ahead of the last session; {menuselection}`File --> Clear Default Session` undoes it.

## Templates

A template is a saved scene with a name, a description, a thumbnail and a rule for which dates it
opens on: **Latest** (ending at the newest data for the scene's layers), **Relative** (ending a set
time before now) or **Fixed** (the dates it was saved with). Opening one gives an Untitled session, so
the template itself is never written over.

- {menuselection}`File --> New from Template...` lists the templates with the range each would open
  on now. A built-in SDO sun pizza template comes with the app.
- {menuselection}`File --> Save as Template...` saves this scene as a template.
- {menuselection}`File --> Template Manager...` (Window > Template Manager... on macOS) manages them: open,
  duplicate, rename, edit, set as startup, share or retire. **Refresh Data** asks HelioFITS Core to
  bring the library up to the newest frames of the template's FITS datasets.

The file format and where templates are kept are in {doc}`reference/templates`.

## Sharing a scene

- {menuselection}`File --> Share Scene...` saves the scene for someone else: your home folder is
  written as `~`, and local files outside the cache are listed first so you can decide whether to go
  ahead.
- {menuselection}`File --> Export Session with Data...` saves the session plus a `.data.zip` of its
  local files, to open on another computer.
- {menuselection}`File --> Copy Provenance` puts the program, its build and each layer's data source
  on the clipboard, for a caption or a methods section.
- {menuselection}`Edit --> Copy Frame` puts the canvas picture on the clipboard, as an 8-bit
  screenshot.

## To Python and other tools

{menuselection}`Layers --> Send to Python` (also the Python button on the layer panel) writes the
active layer's FITS files to `Exports/python-<time>` as double-precision science FITS, plus a manifest
and a sunpy snippet. With a SAMP hub running, each file is also sent as `image.load.fits`.

**SAMP**, on the toolbar, sends messages to other astronomy tools that speak SAMP, the Virtual
Observatory's messaging protocol. The messages Studio accepts are in {doc}`reference/samp`, and the
command line (including the `-export` batch mode) is in {doc}`reference/command-line`.
