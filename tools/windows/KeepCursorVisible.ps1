# Powerful Control Station — experimental Windows cursor wake helper
# Run in an ordinary PowerShell window while using Samsung Second Screen:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\windows\KeepCursorVisible.ps1
# Stop with Ctrl+C. No admin rights or permanently installed services required.
#
# Problem: Windows may hide the mouse cursor after touch input on an attached
# Samsung tablet. KDE Connect Windows moves it via SetCursorPos(), not through
# actual MOUSEEVENTF_MOVE events. Hover keeps working but no pointer is drawn.
# When the cursor position changes, emit a tiny +1 / -1 synthetic mouse move
# using SendInput. Windows should then treat the last pointer modality as mouse.
#
# This is experimental, not a confirmed cure on all Windows/Samsung systems.

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class PowerfulControlStationCursorWake
{
    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X; public int Y; }

    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT
    {
        public uint type;
        public MOUSEINPUT mi;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetCursorPos(out POINT point);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    public static bool TryGetCursorPosition(out POINT point) => GetCursorPos(out point);

    public static bool Wake()
    {
        const uint INPUT_MOUSE = 0;
        const uint MOUSEEVENTF_MOVE = 0x0001;
        INPUT forward = new INPUT
        {
            type = INPUT_MOUSE,
            mi = new MOUSEINPUT { dx = 1, dy = 0, dwFlags = MOUSEEVENTF_MOVE }
        };
        INPUT backward = new INPUT
        {
            type = INPUT_MOUSE,
            mi = new MOUSEINPUT { dx = -1, dy = 0, dwFlags = MOUSEEVENTF_MOVE }
        };
        return SendInput(2, new INPUT[] { forward, backward }, Marshal.SizeOf(typeof(INPUT))) == 2;
    }
}
'@

Write-Host "Cursor wake helper running. Touch Samsung Second Screen, then move using Control Deck. Press Ctrl+C to stop."
$prior = New-Object PowerfulControlStationCursorWake+POINT
$valid = [PowerfulControlStationCursorWake]::TryGetCursorPosition([ref]$prior)

while ($true) {
    Start-Sleep -Milliseconds 40
    $current = New-Object PowerfulControlStationCursorWake+POINT
    if (-not [PowerfulControlStationCursorWake]::TryGetCursorPosition([ref]$current)) {
        $valid = $false
        continue
    }
    if ($valid -and ($current.X -ne $prior.X -or $current.Y -ne $prior.Y)) {
        [void][PowerfulControlStationCursorWake]::Wake()
    }
    $prior = $current
    $valid = $true
}
