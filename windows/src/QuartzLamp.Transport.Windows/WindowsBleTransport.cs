using QuartzLamp.Core;
using QuartzLamp.Protocol;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace QuartzLamp.Transport.Windows;

/// <summary>
/// Windows GATT 传输（P2 真灯验证前为骨架，运行行为未验证）。
/// <list type="bullet">
/// <item>扫描：BluetoothLEAdvertisementWatcher 被动收台，候选=名称非空的 BLE 设备；
///   厂商识别字节 22 20 的原始 AD 偏移过滤（ScanFilter.IsLampBroadcast）待 P2 以真实广播
///   原始字节布局落地（WinRT 提供解析后的 DataSections，原始记录偏移不可直接复刻，
///   需按实测定位于对应 section，过滤纯函数已移植并有测试）。</item>
/// <item>连接：FromBluetoothAddressAsync → FFF0 服务 → FFF3 特征（写/读/notify 同一）。</item>
/// <item>写：每灯 GattOpQueue 串行（对齐 Android 队列语义）；WriteWithResponse。</item>
/// <item>断连：ConnectionStatusChanged → Dropped（意外断连，交会话所有者重连预算）。</item>
/// </list>
/// </summary>
public sealed class WindowsBleTransport : IBleTransport, IDisposable
{
    private BluetoothLEAdvertisementWatcher? _watcher;
    private readonly object _gate = new();

    public sealed record ScanCandidate(ulong Address, string Name, short Rssi);

    /// <summary>扫描候选（含所有命名设备；识别字节过滤在消费侧按 P2 实测结论补充）。</summary>
    public event Action<ScanCandidate>? CandidateFound;

    public void StartScan()
    {
        lock (_gate)
        {
            if (_watcher is not null) return;
            _watcher = new BluetoothLEAdvertisementWatcher
            {
                ScanningMode = BluetoothLEScanningMode.Passive,
            };
            _watcher.Received += OnReceived;
            _watcher.Start();
        }
    }

    public void StopScan()
    {
        lock (_gate)
        {
            if (_watcher is null) return;
            _watcher.Stop();
            _watcher.Received -= OnReceived;
            _watcher = null;
        }
    }

    private void OnReceived(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
        var name = args.Advertisement?.LocalName;
        if (string.IsNullOrEmpty(name)) return;
        CandidateFound?.Invoke(new ScanCandidate(args.BluetoothAddress, name, args.RawSignalStrengthInDBm));
    }

    public async Task<IBleConnection> ConnectAsync(string deviceId, CancellationToken ct)
    {
        var address = ParseAddress(deviceId);
        ct.ThrowIfCancellationRequested();

        var device = await BluetoothLEDevice.FromBluetoothAddressAsync(address).AsTask(ct).ConfigureAwait(false);
        if (device is null)
            throw new IOException($"BLE device not reachable: {deviceId}");
        if (device.ConnectionStatus != BluetoothConnectionStatus.Connected)
        {
            // 触发 GATT 会话建立：访问服务即建立连接。
            var services = await device.GetGattServicesAsync(BluetoothCacheMode.Uncached).AsTask(ct).ConfigureAwait(false);
            if (services.Status != GattCommunicationStatus.Unreachable && services.Services.Count == 0)
                throw new IOException($"GATT services unavailable: {services.Status}");
        }

        var svc = (await device.GetGattServicesForUuidAsync(
                Guid.Parse(LampFrames.ServiceUuid)).AsTask(ct).ConfigureAwait(false))
            .Services.FirstOrDefault()
            ?? throw new IOException($"FFF0 service not found on {deviceId}");
        var ch = (await svc.GetCharacteristicsForUuidAsync(
                Guid.Parse(LampFrames.CharUuid)).AsTask(ct).ConfigureAwait(false))
            .Characteristics.FirstOrDefault()
            ?? throw new IOException($"FFF3 characteristic not found on {deviceId}");

        return new GattBleConnection(deviceId, device, ch);
    }

    private static ulong ParseAddress(string deviceId)
    {
        // "AA:BB:CC:DD:EE:FF" → 48 位地址
        var parts = deviceId.Split(':');
        if (parts.Length != 6) throw new FormatException($"bad BLE address: {deviceId}");
        ulong v = 0;
        foreach (var p in parts) v = (v << 8) | Convert.ToByte(p, 16);
        return v;
    }

    public void Dispose() => StopScan();

    private sealed class GattBleConnection : IBleConnection
    {
        private readonly BluetoothLEDevice _device;
        private readonly GattCharacteristic _characteristic;
        private readonly GattOpQueue _queue = new();

        public event Action? Dropped;

        public string DeviceId { get; }

        public GattBleConnection(string deviceId, BluetoothLEDevice device, GattCharacteristic characteristic)
        {
            DeviceId = deviceId;
            _device = device;
            _characteristic = characteristic;
            _device.ConnectionStatusChanged += OnStatusChanged;
        }

        private void OnStatusChanged(BluetoothLEDevice sender, object args)
        {
            if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
                Dropped?.Invoke();
        }

        /// <summary>经队列串行 WriteWithResponse（FFF3 写/读/notify 同一特征）。</summary>
        public Task<byte[]> WriteAsync(byte[] frame) =>
            _queue.PostAsync(async ct =>
            {
                using var writer = new DataWriter();
                writer.WriteBytes(frame);
                var result = await _characteristic.WriteValueAsync(
                    writer.DetachBuffer(), GattWriteOption.WriteWithResponse).AsTask(ct).ConfigureAwait(false);
                if (result != GattCommunicationStatus.Success)
                    throw new IOException($"GATT write failed: {result}");
                return frame;
            });

        public async ValueTask DisposeAsync()
        {
            await _queue.CloseAsync().ConfigureAwait(false);
            _device.ConnectionStatusChanged -= OnStatusChanged;
            _device.Dispose();
            GC.SuppressFinalize(this);
        }
    }
}
