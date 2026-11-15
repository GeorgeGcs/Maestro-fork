# HarmonyOS Flow integration fixtures

These are synthetic scenarios for the host test suite. `HarmonyOSFlowIntegrationTest`
runs the real YAML reader, Orchestra, Maestro, HarmonyOSDriver and HdcClient. Only
the HDC transport is replaced by a stateful in-process device fixture.

The fixture returns UITest-shaped layouts and PNG files, records actions, and can
return a nonzero device command status while the host HDC exit code is zero. It
can also return a UITest failure message with both exit codes set to zero. The
tests cover Flow variables, conditional sub-Flows, id/text selection, Unicode and
quotes in text input, screenshot artifacts, and failure propagation.

These results do not demonstrate device permissions, native input behavior,
screenshots or application compatibility on a physical HarmonyOS device.

`scroll-flow.yaml` opens a simulated scrollable form. The input keeps its id while
its bounds move from outside the screen, to half visible, to fully visible. The
standard `scrollUntilVisible` command must continue until 100% visibility, then a
conditional sub-flow taps the current bounds and enters text. `scroll-not-found.yaml`
checks that a failed search stops later taps and input.

`flow.yaml` and `scroll-flow.yaml` can run independently with `APP_ID` and
`INPUT_TEXT`. `sequential-flows.yaml` runs both as sub-flows in one session and
checks that a fresh launch resets the page and input focus. This exercises
sequential orchestration, not the CLI's workspace batch scheduler.
