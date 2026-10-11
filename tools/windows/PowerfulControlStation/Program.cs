using System.Drawing;
using System.Runtime.InteropServices;
using Microsoft.Win32;
using System.Windows.Forms;

namespace PowerfulControlStation.Windows;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Any(a => a.Equals("--self-test", StringComparison.OrdinalIgnoreCase)))
        {
            return NativeMouseInput.InputLayoutValid ? 0 : 1;
        }

        using var singleInstance = new Mutex(initiallyOwned: true,
            name: @"Local\PowerfulControlStation.CursorCompanion", createdNew: out bool isFirstInstance);
        if (!isFirstInstance) return 0;

        ApplicationConfiguration.Initialize();
        using var context = new CursorCompanionContext();
        Application.Run(context);
        return 0;
    }
}

internal sealed class CursorCompanionContext : ApplicationContext
{
    private readonly NotifyIcon _tray;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly ToolStripMenuItem _enabledItem;
    private readonly ToolStripMenuItem _startupItem;
    private bool _hasPrior;
    private Point _prior;
    private bool _warned;
    private bool _fixEnabled = true;

    public CursorCompanionContext()
    {
        var menu = new ContextMenuStrip();
        _enabledItem = new ToolStripMenuItem("Enable cursor fix") { Checked = true, CheckOnClick = false };
        _enabledItem.Click += (_, _) => SetFixEnabled(!_fixEnabled);
        menu.Items.Add(_enabledItem);

        _startupItem = new ToolStripMenuItem("Start with Windows")
        {
            Checked = StartupSettings.IsEnabled(),
            CheckOnClick = false
        };
        _startupItem.Click += (_, _) => ToggleStartup();
        menu.Items.Add(_startupItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("About", null, (_, _) => MessageBox.Show(
            "Powerful Control Station — Windows Companion\n\n" +
            "Keeps the mouse pointer visible after Samsung Second Screen touch input.\n" +
            "Uses two balanced SendInput mouse movements when the pointer changes position.\n\n" +
            "No Windows Service or administrator rights required.",
            "Powerful Control Station", MessageBoxButtons.OK, MessageBoxIcon.Information));
        menu.Items.Add("Exit", null, (_, _) => ExitThread());

        _tray = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "Powerful Control Station — cursor fix enabled",
            ContextMenuStrip = menu,
            Visible = true
        };
        _tray.DoubleClick += (_, _) => SetFixEnabled(!_fixEnabled);

        _timer = new System.Windows.Forms.Timer { Interval = 40 };
        _timer.Tick += (_, _) => PollCursor();
        _timer.Start();
    }

    private void SetFixEnabled(bool enabled)
    {
        _fixEnabled = enabled;
        _enabledItem.Checked = enabled;
        _tray.Text = enabled ? "Powerful Control Station — cursor fix enabled" :
            "Powerful Control Station — cursor fix paused";
        _hasPrior = false; // Do not send a synthetic move when re-enabled.
    }

    private void PollCursor()
    {
        if (!_fixEnabled) return;
        if (!NativeMouseInput.TryGetPosition(out var current))
        {
            _hasPrior = false;
            return;
        }

        if (_hasPrior && current != _prior && !NativeMouseInput.WakePointer(current) && !_warned)
        {
            _warned = true;
            _tray.BalloonTipTitle = "Cursor injection blocked";
            _tray.BalloonTipText = "Windows did not accept SendInput. Try in a non-elevated app.";
            _tray.ShowBalloonTip(5000);
        }

        _prior = current;
        _hasPrior = true;
    }

    private void ToggleStartup()
    {
        try
        {
            bool enable = !StartupSettings.IsEnabled();
            StartupSettings.SetEnabled(enable);
            _startupItem.Checked = StartupSettings.IsEnabled();
        }
        catch (Exception ex)
        {
            MessageBox.Show("Could not change startup: " + ex.Message,
                "Powerful Control Station", MessageBoxButtons.OK, MessageBoxIcon.Error);
            _startupItem.Checked = StartupSettings.IsEnabled();
        }
    }

    protected override void ExitThreadCore()
    {
        _timer.Stop();
        _tray.Visible = false;
        _timer.Dispose();
        _tray.ContextMenuStrip?.Dispose();
        _tray.Dispose();
        base.ExitThreadCore();
    }
}

internal static class StartupSettings
{
    private const string RegistryPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "PowerfulControlStation";

    // HKCU Run is interactive and per-user, unlike a Session 0 Windows Service.
    // It needs no elevation or saved credentials and works from a portable EXE.
    private static string Command => "\"" + Application.ExecutablePath + "\"";

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RegistryPath, writable: false);
        return string.Equals(key?.GetValue(ValueName) as string,
            Command, StringComparison.OrdinalIgnoreCase);
    }

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RegistryPath, writable: true)
            ?? throw new InvalidOperationException("Cannot open the current-user startup key.");
        if (enabled) key.SetValue(ValueName, Command, RegistryValueKind.String);
        else key.DeleteValue(ValueName, throwOnMissingValue: false);
    }
}

internal static class NativeMouseInput
{
    private const uint InputMouse = 0;
    private const uint MouseEventMove = 0x0001;
    private const int VirtualLeft = 76; // SM_XVIRTUALSCREEN
    private const int VirtualWidth = 78; // SM_CXVIRTUALSCREEN

    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint { public int X; public int Y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public uint type;
        public MouseInput mi;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetCursorPos(out NativePoint point);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, [In] Input[] inputs, int size);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    // Win32 INPUT is 40 bytes on x64. Test before deployment to catch interop regressions.
    public static bool InputLayoutValid => IntPtr.Size == 8 &&
        Marshal.SizeOf<Input>() == 40 && Marshal.SizeOf<MouseInput>() == 32;

    public static bool TryGetPosition(out Point position)
    {
        if (GetCursorPos(out var native))
        {
            position = new Point(native.X, native.Y);
            return true;
        }
        position = default;
        return false;
    }

    public static bool WakePointer(Point position)
    {
        int left = GetSystemMetrics(VirtualLeft);
        int width = GetSystemMetrics(VirtualWidth);
        // At the rightmost virtual-screen pixel, nudge left then right.
        int step = width > 1 && position.X >= left + width - 1 ? -1 : 1;
        var moves = new[]
        {
            new Input { type = InputMouse, mi = new MouseInput { dx = step, dwFlags = MouseEventMove } },
            new Input { type = InputMouse, mi = new MouseInput { dx = -step, dwFlags = MouseEventMove } }
        };
        return SendInput(2, moves, Marshal.SizeOf<Input>()) == 2;
    }
}
