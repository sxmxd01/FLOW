# FocusFlow for Android

Your FocusFlow study planner as a native Android app (Android 11+ / API 30+), with:

- **Notifications** when a block **starts**, **ends** (with a one-tap *Mark done*), plus an optional *heads-up* before a block (5–30 min). Fixed blocks / recurring classes are included (toggle in Settings). Completed blocks stay quiet.
- **Large home-screen widget** (4×4, resizable): live "NOW / UP NEXT" card with a ticking countdown and progress bar, one-tap completion, and a scrolling timeline of today's blocks. Tap a row to open that block, tap its circle to complete it.
- Same UI as the HTML, bundled offline (`app/src/main/assets/index.html`). **No INTERNET permission.**
- Extras: system Back closes sheets, native share sheet for the day plan/report images, JSON backup export/import, "New block" launcher shortcut, themed icon, splash screen, edge-to-edge layout with keyboard handling.

## Build

1. Open this folder in **Android Studio** (Ladybug / 2024.2 or newer) and let Gradle sync (it downloads Gradle 8.9 and AGP 8.7.3 automatically).
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
