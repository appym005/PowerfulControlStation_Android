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
10. Open the phone's Gboard from **Remote input**, then glide/swipe a few words.
    Confirm the **final** word appears only once on the Windows PC.
11. Verify ordinary tapping, punctuation, space, Enter, Backspace, and
    keyboard shortcuts still work. Try suggestions/autocorrection and an emoji
    to catch doubled characters and broken Unicode.
12. Verify text typed through **Compose and Send** still works separately.

### Swipe typing implementation

The remote keyboard declares a normal Android text input type (with suggestions
and autocorrection enabled). Its `BaseInputConnection` keeps a shadow
`Editable` so keyboards such as Gboard can call `setComposingText`, replace
composing regions, and use `commitText` normally. Only finalized text
is transmitted; corrections are represented as Backspace operations followed by
the replacement text, using the existing KDE Connect mousepad protocol.

The shadow buffer mirrors the text *typed in the current keyboard session*;
it is not a full synchronization of the remote Windows text editor. Editing
arbitrary older remote text via Gboard's cursor/selection gestures is outside
this proof-of-concept and needs separate cursor-state handling.

The GitHub Actions debug build runs the focused `CommittedTextDeltaTest`
regression tests before uploading the APK. End-to-end Gboard swipe behavior
still requires manual testing on a real phone.

## Why this implementation

The Android MousePadPlugin already sends `kdeconnect.mousepad.request`
packets with `scroll: true`, `dx`, and `dy` for two-finger touchpad
scrolling. The new dedicated input surface consumes only touches that start
on its own strip, leaving the existing touchpad and keyboard unmodified.

## Android installation security

The POC debug manifest now inherits **all original KDE Connect permissions and
components** from the main manifest, including Bluetooth, SMS/phone/contacts,
storage, location, notification listener, Accessibility, and remote-keyboard IME.
The earlier debug-only restrictions (25 permission removals and five removed
components) were explicitly reverted. This is a development build: grant only
the permissions you choose to use, and be aware it declares sensitive access.

For an authorized developer-owned device, install using Android Debug Bridge
(ADB) instead of a browser/file manager:

```powershell
adb devices
adb install -r "kdeconnect-android-debug.apk"
```

The debug package remains `com.appym005.powerfulcontrolstation.debug`. ADB
installation **does not disable Play Protect** and is not a guarantee that
Android will permit an APK or grant runtime/special-access permissions.
Do not disable Play Protect to install this app.

## Scope and verification

- Scope: UI strip + event handling + debug app label + CI debug APK.
- We have **not** added a Windows agent or modified the KDE Connect protocol.
- CI proves compilation only; manual testing on a Pixel 9a against the stock
  Windows KDE Connect companion confirmed pairing, remote mouse input, and
  the dedicated scroll strip on home Wi-Fi. The established connection also
  worked through the Pixel-hosted hotspot and survived app restarts.
- Source remains subject to upstream KDE Connect GPL licensing.

## Known issue: intermittent initial Windows discovery on phone-hosted hotspot

**Priority:** Low / revisit if onboarding a *new* Windows computer over a
phone-hosted hotspot fails again. **Currently not reproducible.**

During the first POC test on a Pixel 9a (Android 16, device API 36), with the
Windows laptop connected to the Pixel's mobile hotspot, the POC discovered an
Android tablet but initially did not list the Windows laptop. Original KDE
Connect Android v1.35.17 could already control that laptop on the same hotspot.
The POC was based on KDE Connect Android v1.36.0. "Allow all" trusted networks
was already enabled; disabling the original phone app did not make the Windows
laptop appear at that point.

Later, on home Wi-Fi, the POC discovered and paired with Windows normally; mouse
input and the dedicated scroll wheel both worked. On returning to the phone
hotspot, the POC **remained connected and operational**, including after force
stopping and restarting both apps. Manually unpairing from the phone still left
the Windows machine listed as an available device. Thus we have **not** shown
a persistent inability to discover unpaired PCs on hotspots; it may have been
an intermittent first-time discovery or timing/network-state problem.

The current code's core UDP discovery implementation is essentially unchanged
from upstream v1.35.17, so there is no confirmed regression or root cause.
The Android 17 `ACCESS_LOCAL_NETWORK` permission is not the explanation on
this Android 16 device, and a manual-IP entry workaround was **not tested**.

If reproducible in future: capture `LanLinkProvider`, `MdnsDiscovery`, and
`BackgroundService` logs while first discovering a new laptop over hotspot;
check UDP broadcast/mDNS delivery and network-interface selection before
modifying the implementation. Don't change currently working reconnection or
remote-input behavior without a concrete failure trace.
