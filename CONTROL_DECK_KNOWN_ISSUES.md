# Control Deck — Known issues and validation (October 2026)

## Deferred: selecting text in Windows Terminal
Dragging to highlight/select text in Windows Terminal using the Control Deck trackpad is still not working in the user's setup. The user explicitly requested that we record it and move on. Do not present it as fixed.

## Important: Samsung Second Screen hides the Windows mouse cursor
After touching the Samsung tablet (using Samsung's built-in Second Screen), the Windows pointer may remain interactive but invisible when moved through KDE Connect Android. Moving the physical mouse or laptop trackpad brings the visible cursor back.

The Windows KDE Connect backend currently moves the pointer with SetCursorPos. It does not generate a SendInput mouse-motion event, which may be why Windows stays in touch-pointer mode.

An **experimental** helper is in tools/windows/KeepCursorVisible.ps1: while running on Windows, it watches cursor movement and injects a +1/-1 SendInput pair to restore mouse-pointer modality. It has NOT been tested on the user's Samsung Second Screen and needs confirmation. It runs in the foreground, no admin rights and no Windows system configuration changes.

Potential long-term fix: update/fork KDE Connect's Windows remote-input receiver to use SendInput(MOUSEEVENTF_MOVE) for relative cursor movement, with appropriate handling of desktop scaling, raw input and boundaries.

## Gboard testing required
The IME now retains only a short, recent word (12 seconds, 64 UTF-16 units maximum) to allow replacing a misrecognized swipe word from Gboard suggestions and full-word Backspace immediately after glide input.

Check: suggestion replacement, swipe deletion, conventional typing, emoji, switching focus between Windows textboxes, Enter, Shift+Enter, and interaction with mousepad. Because Windows focus is not exposed by KDE Connect, this is a bounded best-effort behavior, not a fully synchronized remote text editor.

## Shift
Shift is a one-shot modifier for the next Control Deck shortcut, keyboard letter or Enter. For arbitrary long text Gboard determines capitalization; this is not a stateful held Windows key.

## Testing
Automated unit tests and APK CI do not replace testing against the Windows backend and Samsung Second Screen.
