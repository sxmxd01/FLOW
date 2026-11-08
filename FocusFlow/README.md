# FocusFlow for Android

A time-management system (not a to-do list) for Android 11–16+, built from your FocusFlow planner.

**Subjects first:** every block belongs to ONE subject (and optionally a material: “AR – FinalLap”). Subject cards show planned vs done vs the daily goal; partial progress counts proportionally. Add a block in 3 short steps: subject → time → material & note.
**Day flow:** Start day → (live block bar, +10, done, Re-fit when behind) → End day → share the report.
**Planner:** scores every 15-min slot with what it has learned about you; learns from *every* block (rated or not), adapts to your real wake-up / bedtime, re-fits a late start into gaps and breaks, tightens breaks, shortens blocks, and only then slides bedtime (limit is yours) — leftovers roll to tomorrow.
**Also:** home-screen widget, start/end/heads-up alerts + day reminders, separate custom start / end alert sounds (system sound or any audio file, with "force" for phones that ignore channel sounds), typed fields everywhere, undo, backup/restore.

## Build

1. Open this folder in **Android Studio** (Meerkat / 2024.3.1 or newer; the project targets Android 16 / API 36) and let Gradle sync (it downloads Gradle 8.9 and AGP 8.7.3 automatically).
2. Run on a device or emulator running Android 11+.
   Or from a terminal (after Studio generated the wrapper, or with a local Gradle): `gradle assembleDebug`.
3. Long-press the home screen → **Widgets** → **FocusFlow · Day plan**.

The release build is signed with the debug key for convenience; use your own keystore before publishing.

## Build without Android Studio

Push this folder to a GitHub repository. The workflow in `.github/workflows/build.yml` builds the APK automatically:
**Actions tab → latest "Build APK" run → Artifacts → FocusFlow-debug-apk**. Unzip it and install the APK on your phone.

## How it works

| Piece | File |
|---|---|
| WebView shell, insets, permissions, deep links | `MainActivity.kt` |
| JS ⇄ native bridge (`window.FFNative`) | `FocusBridge.kt` |
| Single source of truth (JSON file, crash-safe) | `data/StateStore.kt`, `data/Models.kt` |
| One-alarm chain scheduler (next block boundary) | `notify/Scheduler.kt` |
| Notification channels + content | `notify/Notifier.kt` |
| Boot / clock / permission re-arming | `notify/SystemEventsReceiver.kt` |
| Widget | `widget/FocusWidgetProvider.kt`, `FocusWidgetService.kt`, `WidgetRouterActivity.kt` |

- The web UI keeps its original schema (`focusflow_v2`); it now saves through the bridge to `files/focusflow_state.json`, which alarms and the widget read directly.
- **Only one alarm is ever pending**: the next start/end/heads-up/midnight. When it fires we post what's due, refresh the widget and arm the next one — so edits to your plan are picked up automatically and nothing stale is left behind.
- Widget/notification actions edit the same file and tell the live UI to refresh.

## Permissions

- `POST_NOTIFICATIONS` (Android 13+): asked once on first launch; Settings → Notifications → *Allow* retries.
- `SCHEDULE_EXACT_ALARM` (Android 12+): lets alerts fire on the exact minute. If it's off, alerts still work but can arrive a few minutes late; the app explains this once and Settings shows an *Allow* button.
- `RECEIVE_BOOT_COMPLETED`: re-arm alerts after reboot.

## Moving existing data

If you already have plans in the browser version, there is no automatic bridge between a browser's storage and the app. Start fresh, or paste your old `focusflow_v2` JSON into a `.json` file and use Settings → Backup → **Import**.

## Notes

- Orientation is portrait (the layout is a phone timeline).
- Widget layout adapts: below ~200 dp tall it shows only the header + hero card.
- Battery savers on some OEMs (Xiaomi, Oppo, Huawei…) can kill background alarms; set FocusFlow to *Unrestricted* battery if alerts ever go missing.
