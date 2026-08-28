package no.beint.glimt;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded JPEG conversion executor. Rejection is immediate and input is snapshotted. */
public final class AsyncJpegConverter implements AutoCloseable {
    private final JpegConverter converter;
    private final ThreadPoolExecutor executor;
    private final AtomicLong retained = new AtomicLong();
    private final long maxBytes;

    AsyncJpegConverter(JpegConverter converter, int parallelism, int queuedTasks, long retainedInputBytes) {
        if (parallelism < 1 || parallelism > 256 || queuedTasks < 0 || retainedInputBytes < 1) {
            throw new IllegalArgumentException("Invalid async limits");
        }
        this.converter = converter;
        maxBytes = retainedInputBytes;
        BlockingQueue<Runnable> queue = queuedTasks == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queuedTasks);
        executor = new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.MILLISECONDS, queue,
            Thread.ofPlatform().name("glimt-jpeg-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /** Snapshots admitted input. Limits include queued and currently executing input copies. */
    public CompletableFuture<ConvertedImage> convert(byte[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length < 1 || input.length > converter.limits().maxInputBytes()) {
            return CompletableFuture.failedFuture(new ImageException("Invalid input size"));
        }
        long size = input.length;
        while (true) {
            long previous = retained.get();
            if (size > maxBytes - previous) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Glimt retained input byte limit exceeded"));
            }
            if (retained.compareAndSet(previous, previous + size)) break;
        }
        CompletableFuture<ConvertedImage> future = new CompletableFuture<>();
        try {
            byte[] snapshot = input.clone();
            executor.execute(() -> {
                ConvertedImage converted;
                try {
                    converted = converter.convert(snapshot);
                }
                catch (Throwable error) {
                    retained.addAndGet(-size);
                    future.completeExceptionally(error);
                    if (error instanceof VirtualMachineError fatal) throw fatal;
                    return;
                }
                retained.addAndGet(-size);
                if (!future.isCancelled()) future.complete(converted);
            });
        } catch (RuntimeException | Error error) {
            retained.addAndGet(-size);
            if (error instanceof Error fatal) throw fatal;
            future.completeExceptionally(error);
        }
        return future;
    }

    public long retainedInputBytes() { return retained.get(); }

    @Override
    public void close() {
        executor.shutdown();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try { executor.awaitTermination(1, TimeUnit.DAYS); }
            catch (InterruptedException exception) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
