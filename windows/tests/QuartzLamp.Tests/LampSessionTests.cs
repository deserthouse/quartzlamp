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
        public int DisposeCount;

        public void TriggerDrop() => Dropped?.Invoke();
        public ValueTask DisposeAsync()
        {
            DisposeCount++;
            GC.SuppressFinalize(this);
            return ValueTask.CompletedTask;
        }
    }

    private sealed class FakeTransport : IBleTransport
    {
        // 每次尝试弹出下一个脚本项：Exception（失败）/任意对象（成功返回新连接）。
        // 脚本耗尽=抛 InvalidOperationException（视作一次失败尝试）。
        public Queue<object?> Script = new();
        public List<FakeConnection> Created = new();
        public int Attempts;

        public Task<IBleConnection> ConnectAsync(string deviceId, CancellationToken ct)
        {
            Interlocked.Increment(ref Attempts);
            if (ct.IsCancellationRequested) throw new OperationCanceledException(ct);
            var next = Script.Count > 0 ? Script.Dequeue() : new IOException("script exhausted");
            if (next is Exception ex) return Task.FromException<IBleConnection>(ex);
            if (next is Task<IBleConnection> pending) return pending; // 迟到完成脚本项：原样返回挂起任务
            var conn = new FakeConnection { DeviceId = deviceId };
            lock (Created) Created.Add(conn);
            return Task.FromResult<IBleConnection>(conn);
        }
    }

    private static LampSessionOwner NewOwner(FakeTransport t) =>
        new(t, (_, _) => Task.CompletedTask, TimeSpan.Zero); // 测试零退避，消除时序等待

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
    public async Task 两败一成_Connected且预算余1()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new IOException(), new IOException(), new object()]) };
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A"), "connect");
        var snap = owner.Snapshot("A");
        Assert.Equal(LampSessionState.Connected, snap.State);
        Assert.Equal(1, snap.AttemptsLeft);
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
        await WaitStateAsync(owner, "A", LampSessionState.Connected);

        var snap = owner.Snapshot("A");
        Assert.Equal(2, snap.Generation);
        Assert.Equal(2, t.Attempts);
        Assert.Equal(1, first.DisposeCount); // 旧句柄已释放
        await SettleAsync(owner, t);
    }

    [Fact]
    public async Task 意外断连_重连全败_Failed()
    {
        var t = new FakeTransport { Script = new Queue<object?>([new object(), new IOException()]) };
        var owner = NewOwner(t);
        await WithTimeout(owner.ConnectAsync("A", budget: 2), "connect");

        t.Created[0].TriggerDrop(); // 剩余预算 1 → 重试失败
        await WaitStateAsync(owner, "A", LampSessionState.Failed);
        Assert.Equal(2, t.Attempts);
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
