using System.Collections.Concurrent;

namespace QuartzLamp.Core;

public enum LampSessionState { Disconnected, Connecting, Connected, Reconnecting, Failed }

/// <summary>传输层连接句柄：会话所有者唯一拥有的资源。Dropped=意外断连（非用户发起）。</summary>
public interface IBleConnection : IAsyncDisposable
{
    string DeviceId { get; }
    event Action? Dropped;

    /// <summary>
    /// 断连态闩锁：传输层在构造句柄时就已订阅系统断连事件，而会话所有者要拿到句柄后才订阅
    /// <see cref="Dropped"/>——订阅之前发生的断连事件会丢。会话在订阅后复查本属性补偿。
    /// 实现约定：先置位、再触发事件；一经置位不复位。
    /// </summary>
    bool IsDropped { get; }
}

/// <summary>
/// 传输抽象（Windows GATT 实现见 Transport；测试用脚本化 Fake）。
/// 实现约定：迟到完成（取消令牌已触发后返回成功）是合法行为——所有权契约规定，
/// 被放弃尝试若迟到成功，句柄由会话所有者观察并释放（对齐 Android R44-03 修法：
/// 清理只针对该尝试拿到的句柄，绝无按键查找误杀新连接的可能）；迟到失败被吞掉。
/// </summary>
public interface IBleTransport
{
    Task<IBleConnection> ConnectAsync(string deviceId, CancellationToken ct);
}

public sealed record LampSessionSnapshot(string DeviceId, LampSessionState State, int Generation, int AttemptsLeft);

/// <summary>
/// 每灯会话所有者 —— 对齐 Android Q2 语义：
/// <list type="bullet">
/// <item>ConnectAsync 在首次稳定连接时返回；此后意外断连由后台自动重连（预算逐次推进，终态按最后尝试）；</item>
/// <item>意外断连进入 Reconnecting 退避重试，预算耗尽终态 Failed；退避中用户断开=终止且不复活（WA 修正）；</item>
/// <item>世代/弃任务守卫：迟到成功句柄由所有者释放、绝不触及新连接；</item>
/// <item>用户取消与意外断连语义分离；单设备同时至多一个活动会话，重复调用等待同一会话。</item>
/// </list>
/// </summary>
public sealed class LampSessionOwner : IAsyncDisposable
{
    public const int DefaultBudget = 3;

    private readonly IBleTransport _transport;
    private readonly Func<TimeSpan, CancellationToken, Task> _delay;
    private readonly TimeSpan _backoff;
    private readonly ConcurrentDictionary<string, SessionRun> _runs = new();

    public event Action<LampSessionSnapshot>? StateChanged;

    /// <summary>状态订阅方自身抛出的异常（订阅方问题不得终止会话循环；需要诊断时订阅本事件）。</summary>
    public event Action<Exception>? StateChangedHandlerFailed;

    public LampSessionOwner(IBleTransport transport,
        Func<TimeSpan, CancellationToken, Task>? delay = null,
        TimeSpan? backoff = null)
    {
        _transport = transport;
        _delay = delay ?? Task.Delay;
        _backoff = backoff ?? TimeSpan.FromMilliseconds(500);
    }

    public LampSessionSnapshot Snapshot(string deviceId) =>
        _runs.TryGetValue(deviceId, out var run) ? run.Snapshot : new LampSessionSnapshot(deviceId, LampSessionState.Disconnected, 0, 0);

    /// <summary>发起连接，首次稳定连接（或终态）时返回。此后断连重连在后台进行。</summary>
    public async Task ConnectAsync(string deviceId, int budget = DefaultBudget, CancellationToken ct = default)
    {
        while (true)
        {
            var run = _runs.GetOrAdd(deviceId, _ => new SessionRun(this, deviceId, _transport, _delay, _backoff));
            if (!run.IsFinished)
            {
                await run.StartAsync(budget, ct).ConfigureAwait(false);
                return;
            }
            // 表中残留的是**已终态**的旧 run（预算耗尽 Failed / 发起方取消 Disconnected，且未被显式断开）：
            // 它不会再发起任何连接——按 key+引用原子移除后换新 run，否则重试只会直接返回旧终态
            if (_runs.TryRemove(KeyValuePair.Create(deviceId, run))) continue;
        }
    }

    /// <summary>用户主动断开：终止会话且重连预算不复活。</summary>
    public async Task DisconnectAsync(string deviceId)
    {
        if (_runs.TryRemove(deviceId, out var run))
            await run.UserDisconnectAsync().ConfigureAwait(false);
    }

    public async ValueTask DisposeAsync()
    {
        foreach (var kv in _runs)
            await kv.Value.UserDisconnectAsync().ConfigureAwait(false);
        _runs.Clear();
        GC.SuppressFinalize(this);
    }

    /// <summary>被放弃尝试的观察者：迟到成功则释放该句柄（只清自有），失败/取消吞掉。</summary>
    private static async Task ObserveAbandoned(Task<IBleConnection> attempt)
    {
        try
        {
            var conn = await attempt.ConfigureAwait(false);
            await conn.DisposeAsync().ConfigureAwait(false);
        }
        catch
        {
            // 迟到失败/取消：无资源，吞掉。
        }
    }

    private sealed class SessionRun
    {
        private readonly LampSessionOwner _owner;
        private readonly IBleTransport _transport;
        private readonly Func<TimeSpan, CancellationToken, Task> _delay;
        private readonly TimeSpan _backoff;
        private readonly string _deviceId;
        private readonly TaskCompletionSource _terminal = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly TaskCompletionSource _firstConnected = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly CancellationTokenSource _userCts = new();
        private Task? _runTask;
        private int _generation;
        private int _attemptsLeft;
        private LampSessionState _state = LampSessionState.Connecting;
        private TaskCompletionSource? _dropped;
        private int _started;

        public SessionRun(LampSessionOwner owner, string deviceId, IBleTransport transport,
            Func<TimeSpan, CancellationToken, Task> delay, TimeSpan backoff)
        {
            _owner = owner; _deviceId = deviceId; _transport = transport; _delay = delay; _backoff = backoff;
        }

        public LampSessionSnapshot Snapshot => new(_deviceId, _state, _generation, _attemptsLeft);

        /// <summary>run 是否已走到终点（循环已退出）：终态 run 不再发起任何连接。</summary>
        public bool IsFinished => _terminal.Task.IsCompleted;

        private void Publish()
        {
            var handlers = _owner.StateChanged;
            if (handlers is null) return;
            var snap = Snapshot;
            foreach (var h in handlers.GetInvocationList())
            {
                try
                {
                    ((Action<LampSessionSnapshot>)h)(snap);
                }
                catch (Exception ex)
                {
                    // 订阅方自身的问题不得终止会话循环（否则状态停摆且不落终态）；转交诊断事件
                    _owner.StateChangedHandlerFailed?.Invoke(ex);
                }
            }
        }

        public async Task StartAsync(int budget, CancellationToken ct)
        {
            if (Interlocked.Exchange(ref _started, 1) == 1)
            {
                // 后续调用者：与首个调用者同语义（首连/终态先到先返回）。
                var done = await Task.WhenAny(_firstConnected.Task, _terminal.Task).ConfigureAwait(false);
                if (done == _terminal.Task) await _terminal.Task.ConfigureAwait(false);
                return;
            }

            using var linked = CancellationTokenSource.CreateLinkedTokenSource(_userCts.Token, ct);
            _runTask = RunAsync(budget, linked.Token);
            var settled = await Task.WhenAny(_firstConnected.Task, _terminal.Task).ConfigureAwait(false);
            if (settled == _terminal.Task)
                await _terminal.Task.ConfigureAwait(false);
        }

        private async Task RunAsync(int budget, CancellationToken ct)
        {
            try
            {
                await RunCoreAsync(budget, ct).ConfigureAwait(false);
            }
            finally
            {
                _terminal.TrySetResult();
            }
        }

        private async Task RunCoreAsync(int budget, CancellationToken ct)
        {
            _attemptsLeft = budget;
            _state = LampSessionState.Connecting;
            Publish();

            while (_attemptsLeft > 0)
            {
                _generation++; // 世代按尝试递增（对齐 Android attemptId）
                IBleConnection? conn = null;
                Task<IBleConnection> attempt = _transport.ConnectAsync(_deviceId, ct);
                try
                {
                    conn = await attempt.WaitAsync(ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    // 取消（用户/发起方）：迟到成功由观察者释放，无新尝试。
                    _ = ObserveAbandoned(attempt);
                    _state = LampSessionState.Disconnected;
                    Publish();
                    return;
                }
                catch (Exception)
                {
                    _ = ObserveAbandoned(attempt);
                    _attemptsLeft--;
                    if (_attemptsLeft == 0)
                    {
                        _state = LampSessionState.Failed; // 终态按最后尝试
                        Publish();
                        return;
                    }
                    _state = LampSessionState.Reconnecting;
                    Publish();
                    if (!await BackoffOrStopAsync().ConfigureAwait(false)) return;
                    _state = LampSessionState.Connecting; // 重试尝试进行中：与断连重试路径同样上报
                    Publish();
                    continue;
                }

                // 成功：发布首连，驻留监听断开。预算按"每份连接序列"恢复——对齐 Android：每次意外断连
                // 重新获得完整预算（否则预算是会话生命周期内的累计，灯闪断 N 次即永久 Failed）
                _attemptsLeft = budget;
                _state = LampSessionState.Connected;
                Publish();
                _firstConnected.TrySetResult();
                _dropped = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                Action onDropped = () => _dropped?.TrySetResult();
                conn.Dropped += onDropped;
                // 订阅后复查：句柄在传输层构造时起就可能已断（那时还没人订阅），靠闩锁补偿
                if (conn.IsDropped) _dropped.TrySetResult();

                // 用户断开的等待用独立链接令牌：意外断连分支离开后必须释放，否则注册挂在用户令牌上
                using var userWait = CancellationTokenSource.CreateLinkedTokenSource(_userCts.Token);
                var userDrop = Task.Delay(Timeout.Infinite, userWait.Token);
                var winner = await Task.WhenAny(_dropped.Task, userDrop).ConfigureAwait(false);
                userWait.Cancel();
                conn.Dropped -= onDropped;
                _dropped = null;

                if (winner == userDrop)
                {
                    await conn.DisposeAsync().ConfigureAwait(false);
                    _state = LampSessionState.Disconnected;
                    Publish();
                    return;
                }

                // 意外断连：释放本句柄，转入 Reconnecting；本轮重连拿一份**全新预算**——对齐 Android：
                // 每次意外断连都有 budget 次重连尝试，断连本身不占重连次数
                await conn.DisposeAsync().ConfigureAwait(false);
                _attemptsLeft = budget;
                _state = LampSessionState.Reconnecting;
                Publish();
                if (!await BackoffOrStopAsync().ConfigureAwait(false)) return;
                _state = LampSessionState.Connecting;
                Publish();
            }
        }

        /// <summary>退避；用户断开/取消时发布 Disconnected 并返回 false（终止且不复活）。</summary>
        private async Task<bool> BackoffOrStopAsync()
        {
            try
            {
                await _delay(_backoff, _userCts.Token).ConfigureAwait(false);
                return true;
            }
            catch (OperationCanceledException)
            {
                _state = LampSessionState.Disconnected;
                Publish();
                return false;
            }
        }

        public async Task UserDisconnectAsync()
        {
            await _userCts.CancelAsync().ConfigureAwait(false);
            if (_runTask is not null)
            {
                try { await _runTask.ConfigureAwait(false); }
                catch { /* RunAsync 不外抛 */ }
            }
        }
    }
}
