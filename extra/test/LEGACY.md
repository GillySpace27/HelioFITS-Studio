# Legacy harnesses

The upstream `*Test.java` harnesses under `extra/test/{j2k,event,timelines,model,opengl}` and the
GLSL validator are run by `extra/test/run_tests.py`, not by `ant test`. `ant check-all` runs the
suites `maintenance model j2k timelines event` as step `legacy` and `shaders` as step `shaders`.
The `opengl` and `uri` suites stay manual; `ant check-wcs` runs `wcs`.

A suite listed under "Known red" is reported as `check-all: legacy-<suite>: SKIPPED` instead of
being run. Each row says why it is red and what would make it green. Harnesses are never renamed,
moved or deleted to get a green gate.

## Known red

| Suite | Why it is red (date seen) | What would make it green |
|---|---|---|

## Notes

- The `maintenance` suite was red at 7671c40 because `extra/licenses` tests could not run
  (OpenJPEG natives without a notice mapping); HS-3 fixed it.
- `shaders` needs `glslangValidator` (Homebrew `glslang`, apt `glslang-tools`); without it
  check-all reports the step SKIPPED.
- The `unittest` step needs Pillow (`extra/angle/test_update_angle.py` imports PIL).
