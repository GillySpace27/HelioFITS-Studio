# Storage folders

Two folders can be large: the exports (`~/HFStudio/Exports`, movies, screenshots, Send to Python
folders and their `.hfrec` records) and the persistent download cache (`~/HFStudio/FileCache`).
Settings, sessions and the log always stay under `~/HFStudio`.

Settings > Storage chooses another place for either. The choice is kept in
`~/HFStudio/storage.properties` (keys `exports` and `filecache`), a file of its own because the
folders are created before Settings is read. It takes effect at once; `Directories.EXPORTS` and
`Directories.FILECACHE` ask `StorageFolders` on every call.

- **Missing drive.** A chosen folder that is missing or not writable is not fatal: the default folder
  is used for that session, Settings > Storage and the log say so, and the choice is kept for the next
  launch. A link at the default place whose target is missing (the old workaround) also no longer
  stops startup.
- **Move existing files** (`StorageMove`) moves every top-level entry as a unit, so an export stays beside
  its `.hfrec` / `.hfrec.json` and a `python-*` folder keeps its `hfrecord.json`. Cache files keep their
  hashed names. A file is deleted from the source only after the copy has the same length, nothing at
  the destination is overwritten, download temp files (`*.tmp`) stay, and overlapping folders are refused.
- Check: `extra/test/StorageFoldersCheck.java`.
