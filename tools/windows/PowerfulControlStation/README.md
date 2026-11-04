# Powerful Control Station — Windows Companion

A lightweight, tray-only Windows application that ports the verified `tools/windows/KeepCursorVisible.ps1` behavior to C#/.NET 8 Windows Forms.

## Run

1. Download the `PowerfulControlStation-Windows-x64` artifact from GitHub Actions.
2. Extract it and put `PowerfulControlStation.exe` in a permanent folder (e.g. `D:\Apps\PowerfulControlStation\`).
3. Double-click the executable. It launches without a console window; the icon appears in the system tray.
4. Right-click the icon to toggle **Enable cursor fix**, toggle **Start with Windows**, or **Exit**. Double-click the icon to pause/resume.

**Start with Windows** creates a current-user `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` entry. Unlike a Task Scheduler configuration, this is a simple opt-in per-user login launch requiring no UAC or stored password. This app is not a Windows Service; it needs to run in your interactive desktop session to restore the pointer. Move the executable into its permanent location BEFORE enabling startup. Toggle it off before deleting or moving the executable.

## Behavior

Every 40 ms the app compares `GetCursorPos` with the preceding valid sample. When the cursor moves, it sends two opposite relative `SendInput` mouse movements. At the rightmost edge of the virtual desktop it first moves left, then right, to avoid cursor drift. This reproduces the PowerShell helper tested with Samsung Second Screen and KDE Connect. No network listeners or admin rights.

The app prevents duplicate copies in the same Windows user session, supports pausing from the tray, and displays a one-time warning when input injection fails. Elevated applications can block lower-integrity `SendInput`.

## Build

`dotnet publish tools/windows/PowerfulControlStation/PowerfulControlStation.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugSymbols=false -p:DebugType=None`

The output is a self-contained single-file x64 EXE. .NET Desktop Runtime need not be installed separately. Windows and .NET 8 SDK are required to build locally.

## Test

`PowerfulControlStation.exe --self-test` returns 0 only if Win32 input structs have expected sizes. GitHub Actions runs this check. Full cursor behavior still needs a live test on the affected laptop with Samsung Second Screen; hosted CI cannot reproduce that hardware setup.


## Xbox Gamepad receiver

Run the Windows companion and allow incoming UDP port 28442 on Private networks. Open **Gamepad** (a separate tile from Control Deck) on your Android phone or tablet. The Windows app asks approval for a new phone/tablet once. It remembers approved devices; subsequent frames are signed and sequence-checked. The Android status line offers a manual PC IP fallback if broadcast discovery is blocked.

**Required for actual XInput gamepad output:** install the final ViGEmBus 1.22.0 driver from the official archived release at https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0 . It is third-party retired software and is not bundled or silently installed. The virtual controller appears as an Xbox 360 controller. Run `joy.cpl` on Windows to verify. The companion releases buttons after 350ms without authenticated input. Pair only on trusted networks. To revoke access use the companion tray menu **Forget paired gamepads**.
