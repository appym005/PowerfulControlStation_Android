// SPDX-FileCopyrightText: 2026 Powerful Control Station contributors
// SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;

namespace PowerfulControlStation.Windows;

/// <summary>
/// Standalone local-network gamepad receiver. The Windows user approves pairing via
/// an interactive dialog. Subsequent gamepad frames are HMAC-authenticated and
/// sequence-checked. Frames never go through the KDE Connect keyboard/mouse queue.
/// </summary>
internal sealed class GamepadHost : IDisposable
{
    private const string Prefix = "PCS-GP1";
    private const int Port = 28442;
    private static readonly TimeSpan InputTimeout = TimeSpan.FromMilliseconds(350);
    private readonly ConcurrentDictionary<string, string> _tokens = new();
    private readonly ConcurrentDictionary<string, byte> _pending = new();
    private readonly string _settingsPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "PowerfulControlStation", "gamepads.json");
    private readonly Control _ui = new();
    private readonly UdpClient _udp;
    private readonly CancellationTokenSource _stop = new();
    private readonly System.Threading.Timer _watchdog;
    private readonly object _controllerLock = new();
    private ViGEmClient? _client;
    private IXbox360Controller? _controller;
    private string? _activeId;
    private ulong _lastSequence;
    private DateTime _lastInput = DateTime.MinValue;
    private DateTime _lastDriverError = DateTime.MinValue;
    private bool _neutral = true;
    private bool _disposed;

    private readonly record struct PadReport(
        ushort Buttons, short LX, short LY, short RX, short RY, byte LT, byte RT);

    public GamepadHost()
    {
        // A handle owned by the interactive user's message loop, for approval prompts.
        _ = _ui.Handle;
        LoadPairs();
        _udp = new UdpClient(new IPEndPoint(IPAddress.Any, Port));
        _watchdog = new System.Threading.Timer(_ => Watchdog(), null, 100, 100);
        _ = Task.Run(ReceiveLoop);
    }

    public int PairedCount => _tokens.Count;

    public void ClearPairings()
    {
        lock (_controllerLock)
        {
            Neutralize();
            _activeId = null;
            _lastSequence = 0;
            _tokens.Clear();
            SavePairs();
        }
    }

    private void LoadPairs()
    {
        try
        {
            if (!File.Exists(_settingsPath)) return;
            var data = JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(_settingsPath));
            if (data is null) return;
            foreach (var (id, token) in data)
                if (IsToken(token)) _tokens.TryAdd(id, token);
        }
        catch (Exception) { /* Corrupt data won't enable unauthenticated control. */ }
    }

    private void SavePairs()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_settingsPath)!);
        File.WriteAllText(_settingsPath, JsonSerializer.Serialize(_tokens.ToDictionary(
            item => item.Key, item => item.Value)));
    }

    private async Task ReceiveLoop()
    {
        while (!_stop.IsCancellationRequested)
        {
            try
            {
                var message = await _udp.ReceiveAsync(_stop.Token);
                if (message.Buffer.Length > 700) continue;
                var payload = Encoding.UTF8.GetString(message.Buffer);
                var fields = payload.Split('|');
                if (fields.Length < 2 || fields[0] != Prefix) continue;
                switch (fields[1])
                {
                    case "HELLO": HandleHello(fields, message.RemoteEndPoint); break;
                    case "STATE": HandleState(fields, message.RemoteEndPoint); break;
                }
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (Exception) { /* Receiver survives transient LAN errors. */ }
        }
    }

    private void HandleHello(string[] fields, IPEndPoint sender)
    {
        if (fields.Length != 5 || fields[2].Length is < 8 or > 90) return;
        string deviceId = fields[2];
        if (_tokens.TryGetValue(deviceId, out string? token))
        {
            string signedBody = string.Join("|", fields.Take(4));
            if (!Verify(signedBody, fields[4], token)) return;
            string body = Prefix + "|READY|" + Environment.MachineName;
            Send(sender, body + "|" + Sign(body, token));
            return;
        }

        Send(sender, Prefix + "|OFFER|" + Environment.MachineName);
        if (!_pending.TryAdd(deviceId, 0)) return;
        try
        {
            _ui.BeginInvoke(new Action(() =>
            {
                try
                {
                    var answer = MessageBox.Show(
                        "Allow " + sender.Address + " to control this PC as an Xbox gamepad?\n\n" +
                        "Approve only your own phone/tablet on a trusted network.",
                        "Powerful Control Station · Gamepad pairing",
                        MessageBoxButtons.YesNo, MessageBoxIcon.Question,
                        MessageBoxDefaultButton.Button2);
                    if (answer != DialogResult.Yes) return;
                    string newToken = Base64Url(RandomNumberGenerator.GetBytes(32));
                    _tokens[deviceId] = newToken;
                    SavePairs();
                    // The initial token is exchanged only after interactive Windows approval.
                    Send(sender, Prefix + "|PAIRED|" + Environment.MachineName + "|" + newToken);
                    // UDP can lose a packet: send two more copies while pairing.
                    _ = Task.Run(async () =>
                    {
                        for (int i = 0; i < 2; i++)
                        {
                            await Task.Delay(150);
                            Send(sender, Prefix + "|PAIRED|" + Environment.MachineName + "|" + newToken);
                        }
                    });
                }
                catch (Exception ex)
                {
                    MessageBox.Show("Gamepad pairing failed: " + ex.Message,
                        "Powerful Control Station", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                finally { _pending.TryRemove(deviceId, out _); }
            }));
        }
        catch (Exception) { _pending.TryRemove(deviceId, out _); }
    }

    private void HandleState(string[] fields, IPEndPoint sender)
    {
        if (fields.Length != 12 || !_tokens.TryGetValue(fields[2], out string? token)) return;
        var body = string.Join("|", fields.Take(11));
        if (!Verify(body, fields[11], token)) return;
        if (!ulong.TryParse(fields[3], NumberStyles.None, CultureInfo.InvariantCulture, out ulong sequence) ||
            !ushort.TryParse(fields[4], out ushort buttons) ||
            !short.TryParse(fields[5], out short lx) ||
            !short.TryParse(fields[6], out short ly) ||
            !short.TryParse(fields[7], out short rx) ||
            !short.TryParse(fields[8], out short ry) ||
            !byte.TryParse(fields[9], out byte lt) ||
            !byte.TryParse(fields[10], out byte rt)) return;

        try
        {
            lock (_controllerLock)
            {
                // Do not allow two different phones to fight over a single virtual pad.
                if (_activeId != fields[2])
                {
                    if (_activeId is not null && DateTime.UtcNow - _lastInput < TimeSpan.FromSeconds(2))
                        return;
                    Neutralize();
                    _activeId = fields[2];
                    _lastSequence = 0;
                }
                if (DateTime.UtcNow - _lastInput > TimeSpan.FromSeconds(2)) _lastSequence = 0;
                if (sequence <= _lastSequence) return;
                EnsureController();
                Apply(new PadReport(buttons, lx, ly, rx, ry, lt, rt));
                _lastSequence = sequence;
                _lastInput = DateTime.UtcNow;
                _neutral = false;
            }
        }
        catch (Exception)
        {
            if (DateTime.UtcNow - _lastDriverError > TimeSpan.FromSeconds(3))
            {
                _lastDriverError = DateTime.UtcNow;
                Send(sender, Prefix + "|ERROR|Install ViGEmBus 1.22.0 on Windows, then restart companion");
            }
        }
    }

    private void EnsureController()
    {
        if (_controller is not null) return;
        _client ??= new ViGEmClient(); // Requires the separate ViGEmBus kernel driver.
        var next = _client.CreateXbox360Controller();
        next.AutoSubmitReport = false;
        try
        {
            next.Connect();
            _controller = next;
        }
        catch
        {
            (next as IDisposable)?.Dispose();
            _client.Dispose();
            _client = null;
            throw;
        }
    }

    private void Apply(PadReport report)
    {
        var pad = _controller!;
        pad.SetButtonsFull(report.Buttons);
        pad.LeftThumbX = report.LX;
        pad.LeftThumbY = report.LY;
        pad.RightThumbX = report.RX;
        pad.RightThumbY = report.RY;
        pad.LeftTrigger = report.LT;
        pad.RightTrigger = report.RT;
        pad.SubmitReport();
    }

    private void Neutralize()
    {
        if (_controller is null || _neutral) return;
        try { _controller.ResetReport(); _controller.SubmitReport(); }
        catch (Exception) { }
        _neutral = true;
    }

    private void Watchdog()
    {
        lock (_controllerLock)
        {
            if (!_neutral && DateTime.UtcNow - _lastInput > InputTimeout)
                Neutralize(); // Never leave buttons/sticks held on a packet dropout.
        }
    }

    private static bool IsToken(string token)
    {
        try { return Base64UrlDecode(token).Length == 32; }
        catch (Exception) { return false; }
    }

    private static string Base64Url(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] Base64UrlDecode(string text)
    {
        text = text.Replace('-', '+').Replace('_', '/');
        return Convert.FromBase64String(text.PadRight((text.Length + 3) / 4 * 4, '='));
    }

    private static string Sign(string data, string token) =>
        Convert.ToHexString(HMACSHA256.HashData(Base64UrlDecode(token),
            Encoding.UTF8.GetBytes(data))).ToLowerInvariant();

    private static bool Verify(string data, string hex, string token)
    {
        try
        {
            var actual = Convert.FromHexString(hex);
            var expected = Convert.FromHexString(Sign(data, token));
            return actual.Length == 32 && CryptographicOperations.FixedTimeEquals(actual, expected);
        }
        catch (Exception) { return false; }
    }

    private void Send(IPEndPoint endpoint, string content)
    {
        try
        {
            var bytes = Encoding.UTF8.GetBytes(content);
            _udp.Send(bytes, bytes.Length, endpoint);
        }
        catch (Exception) { /* The next HELLO will retry. */ }
    }

    public static bool ProtocolSelfTest()
    {
        string secret = Base64Url(Enumerable.Range(0, 32).Select(x => (byte)x).ToArray());
        string message = "PCS-GP1|STATE|example|2|4096|0|0|0|0|0|0";
        return Verify(message, Sign(message, secret), secret) &&
            !Verify(message + "1", Sign(message, secret), secret) &&
            !Verify(message, new string('0', 64), secret) &&
            IsToken(secret);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _stop.Cancel();
        _udp.Dispose();
        _watchdog.Dispose();
        lock (_controllerLock)
        {
            Neutralize();
            if (_controller is not null)
            {
                try { _controller.Disconnect(); } catch (Exception) { }
                (_controller as IDisposable)?.Dispose();
            }
            _client?.Dispose();
        }
        _ui.Dispose();
        _stop.Dispose();
    }
}
