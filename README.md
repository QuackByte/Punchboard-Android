# Punchboard for Android

The Android port of [Punchboard](https://github.com/QuackByte/punchboard), the
time card for tracking daily work hours, estimating pay and keeping an eye on
your yearly workload.

> **Status: early.** The data layer, desktop import and reminders engine are
> in place; the UI is being designed (see "Roadmap"). The app currently opens
> to a placeholder screen.

## Features

- **Same data file as the desktop** — the phone keeps its time card in the
  desktop's `punchboard.json` format, so a file moves between desktop and
  phone without conversion. Saving keeps fields this version doesn't know
  about yet, so a newer desktop's settings survive a round trip.
- **Import from Punchboard desktop** — pick the `punchboard.json` your desktop
  saves to (from Drive, Dropbox, Downloads…) and see what it holds (months,
  days logged, sessions, vacation and sick days) before importing.
- **Reminders** — each one fires at most once a day:
  - *Punch-in nudge* on workdays when the card is still empty at your start
    time (up to two hours after it).
  - *Still on the clock?* once today's target is done while punched in.
  - *Forgot to punch out?* if the clock is still running at a set time.
- **Live clock notification** — pinned while you're punched in, with a running
  timer and today's progress.

## Development

Requires JDK 17 and the Android SDK (compile SDK 37).

```bash
# Unit tests: data file round trip, keys, time maths, reminder planning
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug
```

### Project structure

- `model/` — the data file types, key formats (`DayKeys`, `MonthKeys`,
  `PayslipPeriods`) and time maths (`Clock`, `WorkRules`, `workedTime`),
  ported from the desktop's `types.ts`, `utils.ts` and `calculations.ts`.
- `data/` — `DataFileCodec` (lossless JSON read/write), `PunchboardStore`
  (the app's `punchboard.json`, written atomically) and import.
- `reminders/` — `ReminderPlanner` (pure: what's due, when to look again),
  `ReminderEngine` (reads the card, posts notifications, books the next
  check), `ReminderScheduler` (one inexact `AlarmManager` alarm) and
  `ReminderReceiver` (alarms, boot, clock changes).

### Compatibility notes

The desktop's file format has two quirks that must be preserved:

- Day keys are `YYYY-M-D` with a **zero-based** month and no padding
  (`2026-9-14` is 14 October 2026). Month keys are `YYYY-MM`, one-based.
- A running session is written as `"end": null` — the key must be present.

### Stack

Kotlin, Jetpack Compose, kotlinx.serialization. Min SDK 26.

## Roadmap

1. Pick the UI direction (Punchboard look vs. Material 3) and build the
   screens: today/punch clock, time card, day editor, year, pay, settings.
2. Port the period calculations (balanced weeks, carried balance, overtime,
   pay) so reminders use the same daily target as the desktop.
3. Punch in/out from notification actions, a Quick Settings tile and a
   home-screen widget.
4. Optional live sync with the desktop's file.

## License

[MIT](LICENSE)
