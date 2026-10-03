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
| j2k | `AssertionError: cache initialization succeeded despite the persistence lock` (2026-10-02, Linux CI and this Mac; already red at 7671c40d9) | Find whether the JPIP cache still refuses a second holder of its lock, as the test expects, or the test predates a deliberate change |
| timelines | `TimelineDataTest.checkChartPainting` (TimelineDataTest.java:202) throws an `InvocationTargetException` from a reflective constructor (2026-10-02, Linux CI and this Mac; already red at 7671c40d9) | Read the cause under the reflection and bring the test or the constructor it calls back in line |
| event | `AssertionError: central point matches baseline` in the SWEK integration test (2026-10-02, Linux CI and this Mac; already red at 7671c40d9) | Decide whether the fork's CACTus and SWEK changes moved the central point on purpose (new baseline) or by mistake (fix) |

## Notes

- The `maintenance` suite was red at 7671c40 because `extra/licenses` tests could not run
  (OpenJPEG natives without a notice mapping); HS-3 fixed it.
- `shaders` needs `glslangValidator` (Homebrew `glslang`, apt `glslang-tools`); without it
  check-all reports the step SKIPPED.
- The `unittest` step needs Pillow (`extra/angle/test_update_angle.py` imports PIL).
