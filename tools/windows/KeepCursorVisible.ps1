# Powerful Control Station — experimental Windows cursor wake helper
# This script targets Windows PowerShell 5.1 and PowerShell 7.
#
# Run from the directory containing this file:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\KeepCursorVisible.ps1"
#
# Stop with Ctrl+C. No admin rights or persistent policy changes required.
# Purpose: wake a Windows mouse pointer hidden after touching Samsung Second
# Screen when KDE Connect continues moving the pointer via SetCursorPos().
# This remains experimental until verified on the affected laptop.

$ErrorActionPreference = "Stop"

# Use Windows PowerShell-compatible C# syntax. Return a packed 64-bit position,
# avoiding PowerShell's nested struct type lookup altogether.
$cursorWakeSource = @'
using System;
using System.Runtime.InteropServices;

public static class PowerfulControlStationCursorWakeV2
{
    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public MOUSEINPUT mi;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetCursorPos(out POINT point);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    // No allocated custom structs need to cross the PowerShell/C# boundary.
    public static long ReadPosition()
    {
        POINT point;
        if (!GetCursorPos(out point))
            return Int64.MinValue;

        return ((long)point.X << 32) | (uint)point.Y;
    }

    public static bool WakeMousePointer(long packedPosition)
    {
        // Avoid a one-pixel drift when the cursor is exactly on the right edge
        // of the combined virtual display.
        int x = (int)(packedPosition >> 32);
        int virtualLeft = GetSystemMetrics(76); // SM_XVIRTUALSCREEN
        int virtualWidth = GetSystemMetrics(78); // SM_CXVIRTUALSCREEN
        int step = (virtualWidth > 1 && x >= virtualLeft + virtualWidth - 1) ? -1 : 1;

        const uint INPUT_MOUSE = 0;
        const uint MOUSEEVENTF_MOVE = 1;

        INPUT forward = new INPUT();
        forward.type = INPUT_MOUSE;
        forward.mi = new MOUSEINPUT();
        forward.mi.dx = step;
        forward.mi.dwFlags = MOUSEEVENTF_MOVE;

        INPUT backward = new INPUT();
        backward.type = INPUT_MOUSE;
        backward.mi = new MOUSEINPUT();
        backward.mi.dx = -step;
        backward.mi.dwFlags = MOUSEEVENTF_MOVE;

        INPUT[] inputs = new INPUT[] { forward, backward };
        return SendInput(2, inputs, Marshal.SizeOf(typeof(INPUT))) == 2;
    }
}
'@

try {
    if (-not ("PowerfulControlStationCursorWakeV2" -as [type])) {
        Add-Type -TypeDefinition $cursorWakeSource -ErrorAction Stop
    }
} catch {
    Write-Error ("Cannot initialize cursor helper: " + $_.Exception.Message)
    exit 1
}

$cursorWake = [PowerfulControlStationCursorWakeV2]
$prior = $cursorWake::ReadPosition()
$valid = $prior -ne [long]::MinValue
$warned = $false

Write-Host "Cursor wake helper running. Touch Samsung Second Screen, then move the mouse using Control Deck. Ctrl+C stops."

while ($true) {
    Start-Sleep -Milliseconds 40
    $current = $cursorWake::ReadPosition()

    if ($current -eq [long]::MinValue) {
        $valid = $false
        continue
    }

    if ($valid -and $current -ne $prior) {
        $injected = $cursorWake::WakeMousePointer($current)
        if (-not $injected -and -not $warned) {
            Write-Warning "Windows blocked mouse-event injection. If a target application runs elevated, try testing in a normal app first."
            $warned = $true
        }
    }

    $prior = $current
    $valid = $true
}
