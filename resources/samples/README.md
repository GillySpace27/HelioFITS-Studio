# Tour sample images

Bundled with the program and loaded by the Getting Started tour when it starts on an empty canvas
(`src/org/helioviewer/jhv/gui/search/TourSamples.java`). Small, real data with real headers, in
the spirit of sunpy's sample data. Not for science. Add a row for every file, with its source and
the terms it is shared under; `TourSamplesCheck` fails a file without one.

| File | Bytes | Header: TELESCOP / INSTRUME, WAVELNTH, DATE-OBS | Made from | Terms |
|---|---|---|---|---|
| `aia_171_20120831T173411_1024.fits` | 766080 | SDO / AIA_3, 171, 2012-08-31T17:34:11.34 | `extra/test/data/sample.171.fits` (AIA level 1, 4096 x 4096), 4 x 4 mean rebin to 1024 x 1024 int16, RICE compressed, by `python3 extra/tools/make_tour_sample.py extra/test/data/sample.171.fits resources/samples/aia_171_20120831T173411_1024.fits 4` (astropy, numpy). CDELT, CRPIX, X0_MP, Y0_MP and R_SUN scaled; two HISTORY cards say so. | SDO data policy, https://sdo.gsfc.nasa.gov/data/rules.php (read 2026-10-08): SDO images are not copyrighted unless marked, and non-commercial and educational use needs no authorization. Credit: "Courtesy of NASA/SDO and the AIA, EVE, and HMI science teams." Its rule 9 asks that a value-added product be labelled as different from the PI's product (the HISTORY cards) and that the PI be told. |

Header values are read from the file (`astropy.io.fits`, HDU 1); bytes with `ls -l`.
