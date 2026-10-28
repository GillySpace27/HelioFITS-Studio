# Built-in scene templates

Built-in templates go here. Each one is a `.jhv` session file whose state carries a `template`
object (the format is in `docs/templates.md`), with its thumbnail PNG beside it under the file
name the object's `thumbnail` names.

To add one: put `<Name>.jhv` and `<Name>.png` in this folder and add a line `<Name>.jhv` to
`templates.txt`. The app lists built-ins from that file, because a folder inside the jar cannot
be listed. Built-ins are read-only in the app and listed before the user's own templates; a user
template saved under the same name is listed in its place.

## sun-pizza-sdo

The newest two hours of SDO AIA 94, 131, 171, 193, 211, 304, 335, 1600, 1700 and HMI continuum
from the ROB Helioviewer server, cut into one wedge each (docs/pizza.md), labelled in angstrom.
The Helioviewer source ids (8 to 16 and 18) were written from memory of the Helioviewer catalog
and have not yet been checked against a live getDataSources listing; each layer's name says
which channel it should be, so a wrong id shows on open. The thumbnail is a schematic of the
wedges in approximate channel colours, not a rendering of data: replace it with a Save as
Template thumbnail from a real scene.
