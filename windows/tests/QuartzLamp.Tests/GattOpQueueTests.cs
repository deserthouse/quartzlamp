using QuartzLamp.Core;
using Xunit;

namespace QuartzLamp.Tests;

/// <summary>
/// GattOpQueue 反例集 —— 对齐 Android Q1/R20-06 沉淀的坑组：
/// 在途与排队两态 × close/取消/慢 ACK，所有等待有限时间结束，无泄漏。
/// </summary>
public sealed class GattOpQueueTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(5);

    private static async Task<T> WithTimeout<T>(Task<T> task, string what)
    {
        var done = await Task.WhenAny(task, Task.Delay(Patience));
        Assert.True(done == task, $"hung: {what}");
        return await task;
    }

    private static async Task WithTimeout(Task task, string what)
    {
        var done = await Task.WhenAny(task, Task.Delay(Patience));
        Assert.True(done == task, $"hung: {what}");
        await done;
    }

    [Fact]
    public async Task 正常串行_按入队顺序执行()
    {
        await using var q = new GattOpQueue();
        var order = new List<int>();
        var gate = new TaskCompletionSource();

        var t1 = q.PostAsync(async ct => { await gate.Task; order.Add(1); return 1; });
        var t2 = q.PostAsync(async ct => { order.Add(2); return 2; });

        Assert.False(t2.IsCompleted, "op2 must wait behind blocked op1");
        gate.SetResult();
        Assert.Equal(1, await WithTimeout(t1, "t1"));
        Assert.Equal(2, await WithTimeout(t2, "t2"));
        Assert.Equal([1, 2], order);
    }

    [Fact]
    public async Task 在途写_close_等待者结算不悬挂()
    {
        var q = new GattOpQueue();
        var gate = new TaskCompletionSource();
        var t1 = q.PostAsync<int>(async ct => { await gate.Task; return 1; });

        var closeTask = q.CloseAsync();
        await Task.Delay(50);
        gate.SetResult(); // 在途 work 收尾（close 已请求取消令牌，但 work 自行完成）

        await Assert.ThrowsAnyAsync<Exception>(() => WithTimeout(t1, "in-flight waiter on close"));
        await WithTimeout(closeTask, "close");
        Assert.Equal(0, q.PendingCount);
    }

    [Fact]
    public async Task 排队写_close_结算为QueueClosed不执行()
    {
        var q = new GattOpQueue();
        var ran = false;
        var gate = new TaskCompletionSource();
        var t1 = q.PostAsync<int>(async ct => { await gate.Task; return 1; });
        var t2 = q.PostAsync<int>(async ct => { ran = true; return 2; });

        var closeTask = q.CloseAsync();
        await WithTimeout(closeTask, "close");
        gate.SetResult();

        await Assert.ThrowsAsync<QueueClosedException>(() => WithTimeout(t2, "queued waiter on close"));
        Assert.False(ran, "queued op must not execute after close");
        Assert.Equal(0, q.PendingCount);
        await q.DisposeAsync();
    }

    [Fact]
    public async Task close后新操作_立即QueueClosed()
    {
        await using var q = new GattOpQueue();
        await q.CloseAsync();
        await Assert.ThrowsAsync<QueueClosedException>(
            () => q.PostAsync<int>(ct => Task.FromResult(1)));
    }

    [Fact]
    public async Task close幂等_两次不抛()
    {
        var q = new GattOpQueue();
        await q.CloseAsync();
        await WithTimeout(q.CloseAsync(), "second close");
    }

    [Fact]
    public async Task 调用方取消_不撤回入队操作()
    {
        await using var q = new GattOpQueue();
        var ran = false;
        using var cts = new CancellationTokenSource();
        var gate = new TaskCompletionSource();

        var blocker = q.PostAsync<int>(async ct => { await gate.Task; return 0; });
        var cancelled = q.PostAsync(async ct => { ran = true; return 7; }, cts.Token);

        cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => WithTimeout(cancelled, "cancelled waiter"));

        gate.SetResult();
        Assert.Equal(0, await WithTimeout(blocker, "blocker"));
        Assert.True(ran, "cancelled wait must not retract the queued op");
        await Task.Delay(50);
        Assert.Equal(0, q.PendingCount);
    }

    [Fact]
    public async Task work抛异常_等待者收到异常且队列继续()
    {
        await using var q = new GattOpQueue();
        await Assert.ThrowsAsync<InvalidOperationException>(
            () => q.PostAsync<int>(ct => Task.FromException<int>(new InvalidOperationException("boom"))));
        Assert.Equal(2, await WithTimeout(
            q.PostAsync<int>(ct => Task.FromResult(2)), "next op after failure"));
    }

    [Fact]
    public async Task 有界背压_满载时Post等待空位()
    {
        await using var q = new GattOpQueue(capacity: 1);
        var gate = new TaskCompletionSource();
        var t1 = q.PostAsync<int>(async ct => { await gate.Task; return 1; });
        var t2 = q.PostAsync<int>(ct => Task.FromResult(2)); // 占满 capacity=1

        var t3Post = q.PostAsync<int>(ct => Task.FromResult(3)); // 必须等空位
        await Task.Delay(100);
        Assert.False(t3Post.IsCompleted, "post must backpressure when bounded queue is full");

        gate.SetResult();
        Assert.Equal(1, await WithTimeout(t1, "t1"));
        Assert.Equal(2, await WithTimeout(t2, "t2"));
        Assert.Equal(3, await WithTimeout(t3Post, "t3"));
    }
}
