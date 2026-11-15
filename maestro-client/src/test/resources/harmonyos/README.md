# UITest layout fixtures

`layout-merged.json` is synthetic test data, not a device capture. Its shape follows
OpenHarmony UITest commit `f59296c77aac5037a9335c317af58de3a0c22ecb`, inspected on
2026-10-28:

- [ui_driver.cpp](https://github.com/openharmony/testfwk_arkxtest/blob/f59296c77aac5037a9335c317af58de3a0c22ecb/uitest/core/ui_driver.cpp): `DumpUiHierarchy` returns an `attributes`/`children` root by default, or an array of window roots with `listWindows_`.
- [dump_handler.cpp](https://github.com/openharmony/testfwk_arkxtest/blob/f59296c77aac5037a9335c317af58de3a0c22ecb/uitest/core/dump_handler.cpp): nested nodes retain the same shape.
- [ui_model.cpp](https://github.com/openharmony/testfwk_arkxtest/blob/f59296c77aac5037a9335c317af58de3a0c22ecb/uitest/core/ui_model.cpp): attributes are serialized as strings; bounds use `[left,top][right,bottom]`.
- [element_node_iterator_impl.cpp](https://github.com/openharmony/testfwk_arkxtest/blob/f59296c77aac5037a9335c317af58de3a0c22ecb/uitest/server/element_node_iterator_impl.cpp): both `id` and `key` come from `GetInspectorKey`; `accessibilityId` is separate. State values are `true`/`false`; `hint` comes from `GetHint`.
- [server_main.cpp](https://github.com/openharmony/testfwk_arkxtest/blob/f59296c77aac5037a9335c317af58de3a0c22ecb/uitest/server/server_main.cpp): `ParseDumpOption` reads `-p` as the output path for `uitest dumpLayout`.

The parser maps `id` (or nonempty `key`) to Maestro `resource-id`, `hint` to
`hintText`, and `type` to `class`. It keeps missing/empty states unknown. Missing,
hidden, or zero-area bounds do not create a tap target. Malformed bounds fail
parsing; negative screen coordinates are retained rather than silently clipped.
Window arrays get a wrapper without coordinates. Unknown scalar attributes are
retained; optional top-level metadata such as `extraAttrs` is not interpreted.

`UitestLayoutParserTest` tests conversion using the parser and the upstream
`TreeNode` type only. It is included in the device-free host test build.
`UitestMaestroSelectorTest` separately checks real Maestro selectors and
`UiElement` coordinate conversion. It requires the full upstream client build
(`-FullUpstream`) and is excluded from the host test build. Run these integration
tests separately; a host test pass does not imply they passed.

`HierarchyReaderTest` uses a simulated HDC runner to check target binding,
fresh-file transfer, parsing, failures and cleanup. It issues no device commands.
The reader uses a unique file under `/data/local/tmp/` per call and checks the
newly received local file instead of depending on a particular success sentence.
If interrupted, it removes local files and includes the remaining remote path
in the exception's suppressed diagnostics without starting another HDC command.

These tests do not establish compatibility with a particular commercial HarmonyOS
version or resolves overlapping windows. The driver must use the selected
display and refresh the layout after screen changes.
