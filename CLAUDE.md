# CLAUDE.md

This is the Android port of [Punchboard](https://github.com/QuackByte/punchboard)
(Electron/React desktop app). The desktop repo is the source of truth for the
data format and the hour/pay maths: when porting logic, port its tests too.

## Data compatibility

The phone reads and writes the desktop's `punchboard.json` as-is. Never
change key formats (zero-based month in day keys), never drop `"end": null`
on running sessions, and keep `DataFileCodec`'s unknown-field preservation
working. Add a round-trip test for any new field.

## Documentation policy

When a PR adds, changes, or removes a user-facing feature:

1. Update **README.md**'s Features section so it describes what the app does
   *now*. Record deliberate divergences from the desktop and why.
2. Add an entry to **CHANGELOG.md** under `## [Unreleased]` (Keep a Changelog).

## Build constraints

AGP 9 builds this project. `app/build.gradle.kts` applies
`android.application`, `kotlin.compose` and `kotlin.serialization` — under
AGP 9 the `kotlin.android` plugin must not be applied alongside them.

User-facing strings live in `app/src/main/res/values/strings.xml`; don't
hardcode display text in Compose.
