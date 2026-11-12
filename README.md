# Punchboard for Android

The Android port of [Punchboard](https://github.com/QuackByte/punchboard), the
time card for tracking daily work hours, estimating pay and keeping an eye on
your yearly workload. It looks and counts like the desktop app, and it can
share one data file with it.

**Download:** the latest signed APK is on the
[Releases page](https://github.com/QuackByte/Punchboard-Android/releases).
Open it on your phone and allow installing from your browser or file manager.
Android 8.0 or newer.

## Features

- **Punch clock** — punch in and out with one tap; the running session is
  tracked live and saved as a real start/end time. Shows when you'll be done
  for the day, automatic break included, and hours banked or owed.
- **Time card** — the payslip period as a calendar: tap a day to log
  sessions with quick entry (`9-13, 14-18:30`, `8:45 to 17`, or a plain
  total like `7.5` / `7h 30m`) or by editing start/end times, with a note on
  each session. Long-press a workday to mark it vacation or sick, or reset it
  to the schedule. Each week shows its total and the carried balance.
- **Time rules** — automatic unpaid break on long days, punch rounding,
  overtime rate for days off and optionally long workdays.
- **Balanced weeks** — today's target is what's left of the week's hours,
  with hours carried between weeks on a salary (from a start date, resetting
  each January or capped), exactly as on the desktop.
- **Pay** — by the hour or on a fixed salary, tax and deductions, an optional
  second currency, and the payslip's net pay.
- **Year** — hours per period across the year, and vacation taken, booked
  and left against your allowance.
- **Timesheet export** — share the period as CSV or a one-page PDF.
- **Undo** — reset a day or delete a session by mistake? Undo it from the
  toast.
- **Light and dark theme**, following the system or set in Settings.

### Desktop data and sync

- **Import** — pick the `punchboard.json` your desktop app saves to (from
  Drive, Dropbox, Downloads…) and see what it holds before importing.
- **Sync** — keep using that same file: the phone saves back to it and
  picks up desktop changes when it opens. Changes merge day by day; if both
  sides change the same day, the one saved last wins. Fields this version
  doesn't know about are kept, so a newer desktop's settings survive.
- **Backups** — export and restore the whole card as JSON.

### Reminders and quick access

- *Punch-in nudge* on workdays when the card is still empty at your start
  time, with **Punch in** and **In 15 min** buttons.
- *Still on the clock?* once today's target is done, with **Punch out**.
- *Forgot to punch out?* if the clock is still running at a set time.
- **Live clock notification** while punched in, with a running timer,
  today's progress and **Punch out**.
- **Quick Settings tile** and **home-screen widget** to punch in and out from
  anywhere.

## Development

Requires JDK 17 and the Android SDK (compile SDK 37).

```bash
# Unit tests: the desktop's calculation tests, ported, plus file, sync and edit tests
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug
```

### Project structure

- `model/` — the data file types, key formats and every hour/pay
  calculation, ported from the desktop's `types.ts`, `utils.ts`,
  `calculations.ts` and `timesheet.ts` together with their tests.
- `domain/` — `Tracker` reads a card the way the desktop's `useWorkTracker`
  does (months not saved yet, today's balanced target, balances);
  `TrackerEditor` makes the same edits (punches, quick entry, marks,
  settings that carry forward).
- `data/` — `DataFileCodec` (lossless JSON), `PunchboardStore` (the app's
  copy, written atomically), `PunchboardRepository` (every edit goes
  through it; syncs with the shared file) and `JsonMerge` (three-way merge).
- `reminders/`, `punch/`, `widget/` — notifications, punch actions, the
  widget and the Quick Settings tile.
- `ui/` — Jetpack Compose screens in the desktop's design: card-stock paper
  and ink, Bricolage Grotesque and JetBrains Mono.

### Compatibility notes

The desktop's file format has two quirks that must be preserved:

- Day keys are `YYYY-M-D` with a **zero-based** month and no padding
  (`2026-9-14` is 14 October 2026). Month keys are `YYYY-MM`, one-based.
- A running session is written as `"end": null` — the key must be present.

## Releases

Releases are automated with
[release-please](https://github.com/googleapis/release-please), as on the
desktop: merging to `main` with conventional commits opens a release PR, and
merging that PR tags a release, builds a signed APK and attaches it.

Signing uses the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`. For a signed build locally, put a
`keystore.properties` (gitignored) next to this README with `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`. Keep the keystore backed up:
updates only install over an APK signed with the same key.

## License

[MIT](LICENSE). Fonts: Bricolage Grotesque and JetBrains Mono, under the SIL
Open Font License (see `licenses/`). Icons from Lucide (ISC).
