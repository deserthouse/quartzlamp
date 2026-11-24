using QuartzLamp.Core;
using Xunit;

namespace QuartzLamp.Tests;

/// <summary>
/// 会话所有者状态矩阵 —— 期望值对齐 Android ReconnectBudgetTest 语义（两败一成/全败/
/// 退避中断开不复活/意外断连重连/迟到成功 stale 守卫/单飞/双设备独立），传输层脚本化 Fake。
/// ConnectAsync 在首次稳定连接时返回；重连在后台，用轮询等状态。
/// </summary>
public sealed class LampSessionTests
{
    private static readonly TimeSpan WaitLimit = TimeSpan.FromSeconds(5);

    private sealed class FakeConnection : IBleConnection
    {
        public required string DeviceId { get; init; }
        public event Action? Dropped;
        public bool IsDropped { get; private set; }
        public int DisposeCount;

        public void TriggerDrop()
        {
            IsDropped = true; // 闩锁先于事件（与 WindowsBleTransport 同约定）
            Dropped?.Invoke();
        }

        /// <summary>模拟"会话订阅之前系统已报断连"：只置闩锁、不发事件。</summary>
        public void MarkDroppedSilently() => IsDropped = true;

        public ValueTask DisposeAsync()
        {
            DisposeCount++;
            GC.SuppressFinalize(this);
            return ValueTask.CompletedTask;
        }
    }

    private sealed class FakeTransport : IBleTransport
    {
        // 每次尝试弹出下一个脚本项：Exception（失败）/任意对象（成功返回新连接）/
        // IBleConnection 实例（成功返回该既有句柄）/Task<IBleConnection>（迟到完成）。
        // 脚本耗尽=抛 IOException（视作一次失败尝试）。
        public Queue<object?> Script = new();
        public List<FakeConnection> Created = new();
        public int Attempts;

        /// <summary>脚本项：挂起直到令牌取消——模拟"认令牌"的传输层在途尝试（令牌不取消则永不结束）。</summary>
        public static readonly object HangUntilCancelled = new();

        public Task<IBleConnection> ConnectAsync(string deviceId, CancellationToken ct)
        {
            Interlocked.Increment(ref Attempts);
            if (ct.IsCancellationRequested) throw new OperationCanceledException(ct);
            var next = Script.Count > 0 ? Script.Dequeue() : new IOException("script exhausted");
            if (next == HangUntilCancelled)
            {
                var tcs = new TaskCompletionSource<IBleConnection>(TaskCreationOptions.RunContinuationsAsynchronously);
                ct.Register(() => tcs.TrySetCanceled(ct));
                return tcs.Task;
            }
            if (next is Exception ex) return Task.FromException<IBleConnection>(ex);
            if (next is Task<IBleConnection> pending) return pending; // 迟到完成脚本项：原样返回挂起任务
            if (next is IBleConnection existing) return Task.FromResult(existing);
            var conn = new FakeConnection { DeviceId = deviceId };
            lock (Created) Created.Add(conn);
            return Task.FromResult<IBleConnection>(conn);
        }
    }

    private static LampSessionOwner NewOwner(FakeTransport t) =>
        new(t, (_, _) => Task.CompletedTask, TimeSpan.Zero); // 测试零退避，消除时序等待

    /// <summary>等待一组**可观测转移**同时成立。注意不要等待"触发前即已成立"的状态——那不是等待。</summary>
    private static async Task UntilAsync(Func<bool> condition, string what)
    {
        var deadline = DateTime.UtcNow + WaitLimit;
        while (DateTime.UtcNow < deadline)
        {
            if (condition()) return;
            await Task.Delay(10);
        }
        Assert.Fail($"not reached: {what}");
    }

    private static async Task WaitStateAsync(LampSessionOwner owner, string id, LampSessionState state)
    {
        var deadline = DateTime.UtcNow + WaitLimit;
        while (DateTime.UtcNow < deadline)
        {
            if (owner.Snapshot(id).State == state) return;
            await Task.Delay(10);
        }
        Assert.Fail($"state {state} not reached for {id}; now={owner.Snapshot(id).State}");
    }

    private static async Task WithTimeout(Task task, string what)
    {
        var done = await Task.WhenAny(task, Task.Delay(WaitLimit));
        Assert.True(done == task, $"hung: {what}");
    }

    private static async Task<T> WithTimeout<T>(Task<T> task, string what)
    {
        var done = await Task.WhenAny(task, Task.Delay(WaitLimit));
        Assert.True(done == task, $"hung: {what}");
        return await task;
    }

    [Fact]
    public async Task 两败一成_Connected且预算恢复满额()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new IOException(), new IOException(), new object()]) };
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A"), "connect");
        var snap = owner.Snapshot("A");
        Assert.Equal(LampSessionState.Connected, snap.State);
        Assert.Equal(3, snap.AttemptsLeft); // 连上即恢复完整预算：预算按"每份连接序列"计，不在会话生命周期内累计
        await SettleAsync(owner, t);
    }

    [Fact]
    public async Task 全败_终态Failed_不再尝试()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new IOException(), new IOException(), new IOException()]) };
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A"), "connect");
        Assert.Equal(LampSessionState.Failed, owner.Snapshot("A").State);
        Assert.Equal(3, t.Attempts); // 预算=3，恰 3 次尝试
    }

    [Fact]
    public async Task 意外断连_重连成功_世代递增()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new object(), new object()]) };
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A", budget: 3), "connect");
        var first = t.Created[0];
        Assert.Equal(0, first.DisposeCount);

        first.TriggerDrop(); // 意外断连 → 后台重连（脚本第 2 项成功）

        // 等待的是**转移**：旧句柄已释放 + 新尝试已发起 + 终态 Connected。
        // 不能只等 State==Connected——触发断连那一刻它本就成立，等待会同步返回，断言直接暴露在竞态下。
        await UntilAsync(
            () => first.DisposeCount == 1 && t.Attempts == 2 && owner.Snapshot("A").State == LampSessionState.Connected,
            "旧句柄释放 + 新尝试发起 + Connected");

        var snap = owner.Snapshot("A");
        Assert.Equal(2, snap.Generation);
        Assert.Equal(2, t.Attempts);
        Assert.Equal(1, first.DisposeCount); // 旧句柄已释放
        await SettleAsync(owner, t);
    }

    [Fact]
    public async Task 多次闪断每次都重连成功_预算不累计耗尽()
    {
        // budget=2：若预算在会话生命周期内累计，第 2 次闪断即 Failed。
        // 对齐 Android 语义：每次意外断连重新获得完整预算——连续闪断每次秒连成功应一直 Connected。
        var t = new FakeTransport();
        t.Script = new Queue<object?>(new object?[6]); // 1 次首连 + 5 次重连，全部成功
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A", budget: 2), "connect");

        for (var round = 1; round <= 5; round++)
        {
            var current = t.Created[^1];
            var r = round;
            current.TriggerDrop();
            await UntilAsync(
                () => current.DisposeCount == 1 && t.Attempts == 1 + r &&
                      owner.Snapshot("A").State == LampSessionState.Connected,
                $"第 {r} 次闪连后重连成功");
            Assert.Equal(2, owner.Snapshot("A").AttemptsLeft); // 每次连上预算回满
        }

        Assert.Equal(LampSessionState.Connected, owner.Snapshot("A").State);
        await owner.DisconnectAsync("A");
    }

    [Fact]
    public async Task Failed终态后再次Connect_发起新会话并可连上()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new IOException(), new IOException(), new IOException()]) };
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A"), "connect");
        Assert.Equal(LampSessionState.Failed, owner.Snapshot("A").State);

        t.Script.Enqueue(new object()); // 用户重试时灯已就绪
        await WithTimeout(owner.ConnectAsync("A"), "reconnect");
        Assert.Equal(4, t.Attempts); // 必须真的发起新的连接尝试，而不是直接返回旧终态
        Assert.Equal(LampSessionState.Connected, owner.Snapshot("A").State);
        await owner.DisconnectAsync("A");
    }

    [Fact]
    public async Task 首连返回后_重连尝试在途时用户断开_及时生效且不再发布Connected()
    {
        // 首连返回之后会话在后台重连；用户在"重连尝试进行中"（不是退避期）断开。
        // 传输层认令牌：该尝试挂起直到令牌取消。断开必须能取消到它，否则 DisconnectAsync 会挂起到尝试自行结束，
        // 且尝试成功时会话会在用户已要求断开之后再发布一次 Connected。
        var t = new FakeTransport { Script = new Queue<object?>([new object(), FakeTransport.HangUntilCancelled]) };
        var owner = NewOwner(t);
        var states = new System.Collections.Concurrent.ConcurrentQueue<LampSessionState>();
        owner.StateChanged += s => states.Enqueue(s.State);
        await WithTimeout(owner.ConnectAsync("A", budget: 3), "connect");

        t.Created[0].TriggerDrop();
        await UntilAsync(() => t.Attempts == 2, "重连尝试已发起并在途");

        await WithTimeout(owner.DisconnectAsync("A"), "disconnect during in-flight reconnect attempt");
        await UntilAsync(() => states.ToArray().LastOrDefault() == LampSessionState.Disconnected, "循环已停并发布 Disconnected");

        Assert.Equal(
            new[]
            {
                LampSessionState.Connecting, LampSessionState.Connected,          // 首连
                LampSessionState.Reconnecting, LampSessionState.Connecting,       // 意外断连 → 重连尝试在途
                LampSessionState.Disconnected,                                   // 用户断开：到此为止，不得再出现 Connected
            },
            states.ToArray());
    }

    [Fact]
    public async Task 订阅前已断连的句柄_会话识别为意外断连并重连()
    {
        // 传输层返回句柄之前，系统断连事件已经发过（事件丢失）：仅 IsDropped 闩锁为真，之后不再发事件。
        // 会话必须靠"订阅后复查"识别，否则永久停在 Connected。
        var t = new FakeTransport();
        var preDropped = new FakeConnection { DeviceId = "A" };
        preDropped.MarkDroppedSilently();
        t.Script.Enqueue(preDropped);
        t.Script.Enqueue(new object());
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A", budget: 2), "connect");

        await UntilAsync(() => t.Attempts == 2 && preDropped.DisposeCount == 1, "订阅前已断的句柄被释放并发起重连");
        Assert.Equal(LampSessionState.Connected, owner.Snapshot("A").State);
        await owner.DisconnectAsync("A");
    }

    [Fact]
    public async Task 状态订阅方抛异常_不终止会话循环()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new object(), new object()]) };
        var owner = NewOwner(t);
        Exception? reported = null;
        owner.StateChangedHandlerFailed += ex => reported = ex;
        owner.StateChanged += _ => throw new InvalidOperationException("subscriber bug");

        await WithTimeout(owner.ConnectAsync("A", budget: 2), "connect");
        Assert.IsType<InvalidOperationException>(reported);

        t.Created[0].TriggerDrop();
        await UntilAsync(() => t.Attempts == 2 && t.Created[0].DisposeCount == 1, "订阅方异常后重连循环仍在推进");
        await owner.DisconnectAsync("A");
    }

    [Fact]
    public async Task 失败后重试期间_状态序列为Connecting_Reconnecting_Connected()
    {
        var states = new System.Collections.Concurrent.ConcurrentQueue<LampSessionState>();
        var t = new FakeTransport { Script = new Queue<object?>([new IOException(), new object()]) };
        var owner = NewOwner(t);
        owner.StateChanged += s => states.Enqueue(s.State);

        await WithTimeout(owner.ConnectAsync("A", budget: 3), "connect");
        // 重试尝试进行中应上报 Connecting（与断连重试路径一致），而不是全程停在 Reconnecting
        Assert.Equal(
            new[] { LampSessionState.Connecting, LampSessionState.Reconnecting, LampSessionState.Connecting, LampSessionState.Connected },
            states.ToArray());
        await owner.DisconnectAsync("A");
    }

    [Fact]
    public async Task 意外断连_重连尝试次数等于预算_全败则Failed()
    {
        // budget=2：断连本身不占重连次数——断连后应有 **2 次**重连尝试（共 1+2 次连接调用），全败才 Failed
        var t = new FakeTransport { Script = new Queue<object?>([new object(), new IOException(), new IOException()]) };
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A", budget: 2), "connect");

        t.Created[0].TriggerDrop();
        await WaitStateAsync(owner, "A", LampSessionState.Failed); // Failed 在触发前不成立：这是有效等待
        Assert.Equal(3, t.Attempts);
        Assert.Equal(0, owner.Snapshot("A").AttemptsLeft);
    }

    [Fact]
    public async Task 意外断连_预算内第三次成功_恰好三次尝试()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new object(), new IOException(), new IOException(), new object()]) };
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A", budget: 3), "connect");

        t.Created[0].TriggerDrop();
        await UntilAsync(() => t.Attempts == 4 && owner.Snapshot("A").State == LampSessionState.Connected &&
                             t.Created.Count == 2 && t.Created[1].DisposeCount == 0,
            "两败一成：第 3 次重连成功");
        Assert.Equal(4, t.Attempts); // 1 次首连 + 恰好 budget=3 次重连
        Assert.Equal(LampSessionState.Connected, owner.Snapshot("A").State);
        await owner.DisconnectAsync("A");
    }

    [Fact]
    public async Task 用户断开_终止且不复活()
    {
        // 真实长退避：把会话冻结在 Reconnecting 退避期，验证用户断开不复活。
        var t = new FakeTransport { Script = new Queue<object?>([new IOException(), new IOException()]) };
        var owner = new LampSessionOwner(t, Task.Delay, TimeSpan.FromSeconds(30));
        var connect = owner.ConnectAsync("A", budget: 3);

        var deadline = DateTime.UtcNow + WaitLimit;
        while (owner.Snapshot("A").State != LampSessionState.Reconnecting && DateTime.UtcNow < deadline)
            await Task.Delay(10);
        Assert.Equal(LampSessionState.Reconnecting, owner.Snapshot("A").State); // 已入退避

        await WithTimeout(owner.DisconnectAsync("A"), "user disconnect");
        Assert.Equal(LampSessionState.Disconnected, owner.Snapshot("A").State);
        await Task.Delay(200);
        Assert.Equal(1, t.Attempts); // 退避中断开：不复活，无第二次尝试
    }

    [Fact]
    public async Task 迟到成功_stale守卫_弃任务句柄由所有者释放()
    {
        // attempt1 挂在迟到 TCS 上（Fake 不看取消令牌）→ 用户取消 → 之后放行成功：
        // 该句柄必须被所有者的弃任务观察者释放，且无新尝试。
        var lateTcs = new TaskCompletionSource<IBleConnection>(TaskCreationOptions.RunContinuationsAsynchronously);
        var t = new FakeTransport();
        t.Script.Enqueue(lateTcs.Task);

        var owner = NewOwner(t);
        using var userCts = new CancellationTokenSource();
        var connect = owner.ConnectAsync("A", budget: 3, userCts.Token);

        await Task.Delay(30); // attempt1 挂起
        userCts.Cancel();
        await WithTimeout(connect, "connect");
        var dl = DateTime.UtcNow + WaitLimit; // connect 首连即返回：等后台终态发布
        while (owner.Snapshot("A").State != LampSessionState.Disconnected && DateTime.UtcNow < dl)
            await Task.Delay(10);
        Assert.Equal(LampSessionState.Disconnected, owner.Snapshot("A").State);

        var late = new FakeConnection { DeviceId = "A" };
        lateTcs.SetResult(late); // 迟到成功
        var deadline = DateTime.UtcNow + WaitLimit;
        while (late.DisposeCount == 0 && DateTime.UtcNow < deadline) await Task.Delay(10);
        Assert.Equal(1, late.DisposeCount); // 所有者释放了它
        Assert.Equal(1, t.Attempts);
    }

    [Fact]
    public async Task 单设备重复Connect_等待同一会话不并发()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new object()]) };
        var owner = NewOwner(t);
        var c1 = owner.ConnectAsync("A");
        var c2 = owner.ConnectAsync("A");
        await WithTimeout(c1, "c1");
        await WithTimeout(c2, "c2");
        Assert.Equal(1, t.Attempts);
        await SettleAsync(owner, t);
    }

    [Fact]
    public async Task 双设备独立会话()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new object(), new IOException(), new IOException()]) };
        var owner = NewOwner(t);
        var a = owner.ConnectAsync("A", budget: 1);
        var b = owner.ConnectAsync("B", budget: 2);
        await WithTimeout(a, "A");
        await WithTimeout(b, "B");
        Assert.Equal(LampSessionState.Connected, owner.Snapshot("A").State);
        Assert.Equal(LampSessionState.Failed, owner.Snapshot("B").State);
        await SettleAsync(owner, t);
    }

    /// <summary>收尾：触发剩余连接断开并等会话终态，避免遗留后台任务跨测试。</summary>
    private static async Task SettleAsync(LampSessionOwner owner, FakeTransport t)
    {
        List<FakeConnection> snapshot;
        lock (t.Created) snapshot = t.Created.ToList();
        foreach (var c in snapshot.Where(c => c.DisposeCount == 0)) c.TriggerDrop();
        var deadline = DateTime.UtcNow + WaitLimit;
        while (DateTime.UtcNow < deadline)
        {
            var states = new[] { "A", "B" }
                .Select(id => owner.Snapshot(id).State)
                .Where(s => s is LampSessionState.Failed or LampSessionState.Disconnected or LampSessionState.Disconnected);
            if (snapshot.All(c => c.DisposeCount >= 1) && owner.Snapshot("A").State is not LampSessionState.Connected
                && owner.Snapshot("A").State is not LampSessionState.Reconnecting) return;
            await Task.Delay(10);
        }
    }
}
