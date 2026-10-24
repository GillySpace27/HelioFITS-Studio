# Built-in scene templates

Built-in templates go here. Each one is a `.jhv` session file whose state carries a `template`
object (the format is in `docs/templates.md`), with its thumbnail PNG beside it under the file
name the object's `thumbnail` names.

To add one: put `<Name>.jhv` and `<Name>.png` in this folder and add a line `<Name>.jhv` to
`templates.txt`. The app lists built-ins from that file, because a folder inside the jar cannot
be listed. Built-ins are read-only in the app and listed before the user's own templates; a user
template saved under the same name is listed in its place.
