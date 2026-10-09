# Powerful Control Station — input POC

This branch keeps KDE Connect's original pairing and mousepad/keyboard plugin intact.
It adds a **dedicated one-finger vertical scroll strip** on the right side of
Remote Input. Swipes are passed to the existing `MousePadPlugin.sendScroll(0, dy)`
packet method, so the stock KDE Connect companion on Windows is sufficient.

## Testing on Android + Windows

1. Install KDE Connect on Windows and ensure the Windows computer and Android
   phone are on the same Wi-Fi network.
2. Build or download the **debug** APK from GitHub Actions, and install on Android.
   The debug app is called **Powerful Control Station (POC)** and uses the
   unique debug application ID `com.appym005.powerfulcontrolstation.debug`, so it
   installs alongside both regular KDE Connect (`org.kde.kdeconnect_tp`) and
   KDE Connect's own debug build. Release builds use
   `com.appym005.powerfulcontrolstation`.
3. Pair the POC app with the Windows KDE Connect companion separately.
4. Open the paired computer in the Android POC app, then open **Remote input**.
5. Test pointer motion by moving one finger in the main touchpad area.
6. Test single/double click, left/right/middle buttons, and dragging.
7. Open the keyboard from the Remote Input menu; test ordinary typing,
   Backspace/Enter, and Ctrl/Alt/Shift shortcuts.
8. Swipe **up and down using one finger on the right-hand SCROLL strip**.
   Confirm that the target window scrolls without moving the mouse pointer.
9. Check the existing scroll direction and sensitivity preferences and make
   sure they also affect the dedicated strip.

## Why this implementation

The Android MousePadPlugin already sends `kdeconnect.mousepad.request`
packets with `scroll: true`, `dx`, and `dy` for two-finger touchpad
scrolling. The new dedicated input surface consumes only touches that start
on its own strip, leaving the existing touchpad and keyboard unmodified.

## Android installation security

The POC debug manifest deliberately removes unrelated sensitive permissions
(SMS, call log, contacts, phone state, background location, broad file access,
installation, camera, microphone, Bluetooth, and accessibility), along with
the SMS receiver, remote-keyboard receiving IME, notification listener, and
accessibility service. The Android keyboard **sending** implementation used
by Remote Input is still present.

This is a **remote-input-only** debug build: do not use it to test KDE Connect
features which require the removed permissions or services.

If Google Play Protect blocks an older debug APK, do not disable Play Protect
or override its warning. Install a newly built POC APK only after checking
the permissions and installer label. A reduced permission set may reduce
security warnings but does not guarantee Play Protect approval.

## Scope and verification

- Scope: UI strip + event handling + debug app label + CI debug APK.
- We have **not** added a Windows agent or modified the KDE Connect protocol.
- A successful CI build demonstrates compilation, not real-world input latency,
  pairing, scrolling, or keyboard correctness; those require an Android device
  paired with the Windows companion.
- Source remains subject to upstream KDE Connect GPL licensing.
