# UnlockShot

An Xposed module (Vector / libxposed API 101-102) that unlocks screenshots blocked by `FLAG_SECURE` and disables Android's screenshot / screen-record detection callbacks.

## Credits

UnlockShot is a derivative of **DisableFlagSecure ("Enable Screenshot")** by the **LSPosed team** — Wang Han, tehcneko and all contributors:
https://github.com/LSPosed/DisableFlagSecure

The original and this derivative are both licensed under **GNU GPL v3.0** (see [LICENSE](LICENSE)). All credit for the core hooks goes to the original authors — this fork only adds diagnostics and ROM tolerance.

من القلب شكراً لفريق LSPosed — Wang Han وtehcneko وكل المساهمين. كل الفضل في الهوكس الأساسية ليكم، وسلامنا ليكم 🌷 التعديل ده بس بيضيف تشخيص وتسامح مع الرومات المختلفة، والأصل والنسخة دي الاتنين تحت رخصة GPL v3.0.

## What it does

- Removes `FLAG_SECURE` so blocked apps can be screenshotted.
- Blocks screenshot / screen-recording detection callbacks so apps don't get notified.
- Runs at system level (scope: `system` + `com.android.systemui`), the officially recommended scope for this kind of module.

## Differences from upstream

- **Diagnostic logging:** every hook attempt logs `OK` or `FAIL` with the reason to the Xposed log (Vector → Logs tab), so you can see exactly which hooks applied on your device/ROM instead of guessing.
- **ROM-tolerant probing:** the `ScreenCapture` / `ScreenshotHardwareBuffer` hooks resolve class and field names by probing known layouts (newest first) instead of only branching on SDK version, so newer or renamed system layouts fall back cleanly instead of silently doing nothing.
- Renamed package and entry point (`com.abdul.unlockshot`).

## Requirements

- Rooted Android device with Vector (libxposed API 101-102) installed, e.g. via KernelSU.
- Android 14+ (minSdk 34).

## Installation

1. Download the APK from the [Releases](../../releases) page.
2. Install it, enable UnlockShot in Vector's module list, and keep the scope on the recommended entries (System Framework / System UI).
3. Reboot.
4. Check Vector → Logs for `UnlockShot` lines: each hook reports `OK` or `FAIL` with the reason.

## Tested on

- Samsung Galaxy A56 5G (SM-A566B), One UI 9.0, Android 17, EGY firmware, KernelSU + Vector — working on first try.

It may or may not work on other devices/ROMs. The log output is there to tell you exactly what applied on yours.

## Warnings

- This module hooks inside the system. A misbehaving module can cause a bootloop: if that happens, KernelSU documents disabling modules by pressing volume-down several times early during boot (https://kernelsu.org/guide/rescue-from-bootloop.html).
- If a hooked callback throws at call time inside an app that registered it, the exception can propagate into `system_server`. Test on your own device and keep a recovery path in mind.
- No guarantees: use at your own risk.

## Building

Manual pipeline, no Gradle: `javac 17` + `d8` + `aapt2` + `zipalign` + `apksigner`, compiled against `android.jar` and `io.github.libxposed:api:102.0.0` (compile-only). The xposed entry metadata lives in `xposed/META-INF/xposed/` and is packaged into the APK at `META-INF/xposed/`.

## License

GNU GPL v3.0 — same as upstream. See [LICENSE](LICENSE).
