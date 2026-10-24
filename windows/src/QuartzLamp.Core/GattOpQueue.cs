using System.Collections.Concurrent;
using System.Threading.Channels;

namespace QuartzLamp.Core;

/// <summary>队列已关闭后到达的操作/等待：普通断连不算异常，折叠为显式结果。</summary>
public sealed class QueueClosedException : Exception
{
    public QueueClosedException() : base("GATT operation queue is closed.") { }
}

/// <summary>
/// 每灯 GATT 操作串行队列 —— 对齐 Android GattOpQueue（Q1 终态所有权语义）：
/// <list type="bullet">
/// <item>注册表同时持有排队与在途操作，只有执行终结（finally）或 close 结算后才注销，不悬挂任何等待者；</item>
/// <item>close 与消费循环取消无论谁先，全部等待都有限时间结束；close 幂等；</item>
/// <item>调用方取消等待不撤回已入队操作（R20-06：取消≠撤回，操作照常执行、结果无人观察）；</item>
/// <item>有界背压：队列满时 PostAsync 等待空位，不无界堆积。</item>
/// </list>
/// </summary>
public sealed class GattOpQueue : IAsyncDisposable
{
    private abstract class OpEntry
    {
        public Guid Id { get; init; }
        public abstract Task ExecuteAsync(CancellationToken token);
        public abstract void FailClosed();
    }

    private sealed class OpEntry<T> : OpEntry
    {
        private readonly Func<CancellationToken, Task<T>> _work;
        private readonly TaskCompletionSource<T> _tcs = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public OpEntry(Guid id, Func<CancellationToken, Task<T>> work)
        {
            Id = id;
            _work = work;
        }

        public Task<T> Waiter => _tcs.Task;

        public override async Task ExecuteAsync(CancellationToken token)
        {
            try
            {
                var result = await _work(token).ConfigureAwait(false);
                _tcs.TrySetResult(result);
            }
            catch (OperationCanceledException)
            {
                _tcs.TrySetCanceled();
            }
            catch (Exception ex)
            {
                _tcs.TrySetException(ex);
            }
        }

        public override void FailClosed() => _tcs.TrySetException(new QueueClosedException());
    }

    public const int DefaultCapacity = 32;

    private readonly Channel<OpEntry> _channel;
    private readonly ConcurrentDictionary<Guid, OpEntry> _pending = new();
    private readonly CancellationTokenSource _closeCts = new();
    private readonly Task _consumer;
    private readonly TaskCompletionSource _closedTcs = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private OpEntry? _inFlight;
    private Task? _inFlightExecution;
    private int _state; // 0=open, 1=closing, 2=closed

    public GattOpQueue(int capacity = DefaultCapacity)
    {
        _channel = Channel.CreateBounded<OpEntry>(new BoundedChannelOptions(capacity)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
        });
        _consumer = Task.Run(ConsumeAsync);
    }

    /// <summary>内部观察：注册表计数（防泄漏断言用）。</summary>
    internal int PendingCount => _pending.Count;

    /// <summary>入队并等待结果。调用方取消仅放弃等待，不撤回操作。</summary>
    public async Task<T> PostAsync<T>(Func<CancellationToken, Task<T>> work, CancellationToken ct = default)
    {
        if (Volatile.Read(ref _state) != 0) throw new QueueClosedException();

        var id = Guid.NewGuid();
        OpEntry<T> entry = new(id, work);
        _pending[id] = entry;

        try
        {
            await _channel.Writer.WriteAsync(entry, CancellationToken.None).ConfigureAwait(false);
        }
        catch (ChannelClosedException)
        {
            _pending.TryRemove(id, out _);
            throw new QueueClosedException();
        }

        return await entry.Waiter.WaitAsync(ct).ConfigureAwait(false);
    }

    private async Task ConsumeAsync()
    {
        try
        {
            while (await _channel.Reader.ReadAsync(_closeCts.Token).ConfigureAwait(false) is { } entry)
            {
                _inFlight = entry;
                _inFlightExecution = entry.ExecuteAsync(_closeCts.Token);
                try
                {
                    await _inFlightExecution.ConfigureAwait(false);
                }
                finally
                {
                    _pending.TryRemove(entry.Id, out _);
                    _inFlight = null;
                    _inFlightExecution = null;
                }
            }
        }
        catch (OperationCanceledException) when (_closeCts.IsCancellationRequested)
        {
            // close 触发：残留排队项由 CloseAsync drain 并结算。
        }
        catch (ChannelClosedException)
        {
            // Writer 完成：正常关闭路径。
        }
    }

    /// <summary>
    /// 关闭队列：阻止新操作 → 排队项不执行、在途项等待者立即结算为 QueueClosed
    /// （close 不等待不配合取消的 work；其结果随后被丢弃，与 Android「取消消费 Job」对齐）→ 清扫注册表。幂等。
    /// </summary>
    public async Task CloseAsync()
    {
        if (Interlocked.Exchange(ref _state, 1) != 0)
        {
            await _closedTcs.Task.ConfigureAwait(false);
            return;
        }

        _channel.Writer.TryComplete();
        await _closeCts.CancelAsync().ConfigureAwait(false);

        // 排队残留：不执行，直接结算。
        while (_channel.Reader.TryRead(out var queued))
        {
            if (_pending.TryRemove(queued.Id, out _)) queued.FailClosed();
        }

        // 清扫一切残留（含在途未终结的：等待者立即收到 QueueClosed，
        // 执行体稍后完成时 TrySet 均为 no-op，消费者 finally 注销亦为 no-op）。
        foreach (var kv in _pending)
        {
            if (_pending.TryRemove(kv.Key, out _)) kv.Value.FailClosed();
        }

        Volatile.Write(ref _state, 2);
        _closedTcs.TrySetResult();
    }

    public async ValueTask DisposeAsync()
    {
        await CloseAsync().ConfigureAwait(false);
        GC.SuppressFinalize(this);
    }
}
