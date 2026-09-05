package no.beint.glimt;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded CPU executor for AVIF conversion.
 * Rejection is immediate; cancellation never pretends to stop native code.
 */
public final class AsyncImageConverter implements AutoCloseable {
    private final ImageConverter converter;
    private final ThreadPoolExecutor executor;
    private final AtomicLong retained = new AtomicLong();
    private final long maxBytes;
    AsyncImageConverter(ImageConverter converter, int parallelism, int queuedTasks, long retainedInputBytes) {
        if (parallelism < 1 || parallelism > 256 || queuedTasks < 0 || retainedInputBytes < 1)
            throw new IllegalArgumentException("Invalid async limits");
        this.converter = converter; maxBytes = retainedInputBytes;
        BlockingQueue<Runnable> queue = queuedTasks == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queuedTasks);
        executor = new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.MILLISECONDS, queue,
            Thread.ofPlatform().name("glimt-encode-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    /**
     * Admits a conversion and snapshots its input before returning.
     * Limits include queued and currently executing input copies.
     *
     * @param input compressed image bytes
     * @return a future completed with the converted AVIF, or exceptionally when rejected or conversion fails
     */
    public CompletableFuture<ConvertedImage> convert(byte[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length < 1 || input.length > converter.limits().maxInputBytes()) return CompletableFuture.failedFuture(new ImageException("Invalid input size"));
        long size = input.length;
        while (true) {
            long previous = retained.get();
            if (size > maxBytes - previous) return CompletableFuture.failedFuture(new RejectedExecutionException("Glimt retained input byte limit exceeded"));
            if (retained.compareAndSet(previous, previous + size)) break;
        }
        CompletableFuture<ConvertedImage> future = new CompletableFuture<>();
        try {
            byte[] snapshot = input.clone();
            executor.execute(() -> {
                // Cancellation or a timeout can finish a future while it waits
                // in the queue. Active native work still runs to completion.
                if (future.isDone()) {
                    retained.addAndGet(-size);
                    return;
                }
                ConvertedImage converted;
                try {
                    converted = converter.convert(snapshot);
                } catch (Throwable error) {
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
    /**
     * Reports current input-memory pressure.
     * @return the number of input bytes retained by queued and active conversions
     */
    public long retainedInputBytes() { return retained.get(); }
    /**
     * Stops admission and waits for all admitted work, restoring the caller's interrupt flag afterward.
     * Active native calls cannot be interrupted safely. Do not close from this executor's own callback.
     */
    @Override public void close() {
        executor.shutdown();
        boolean interrupted = false;
        // ExecutorService.close() invokes shutdownNow() on interruption, which
        // would silently discard queued tasks and leave their futures unresolved.
        while (!executor.isTerminated()) {
            try { executor.awaitTermination(1, TimeUnit.DAYS); }
            catch (InterruptedException exception) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
