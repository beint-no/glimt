package no.beint.glimt.test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import no.beint.glimt.*;
import no.beint.glimt.spi.ImageDecoder;
import no.beint.glimt.spi.PixelImage;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class AsyncCancellationTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void skipsCompletedQueuedWorkAndReleasesAdmission(boolean jpeg, boolean timeout) throws Exception {
        // A controlled decoder proves the queued request never reaches a codec,
        // without racing native conversion durations or relying on timing assertions.
        CountingDecoder.calls.set(0);
        CountingDecoder.entered = new CountDownLatch(1);
        CountingDecoder.release = new CountDownLatch(1);
        Path service = temporary.resolve("decoder-service");
        Files.writeString(service, CountingDecoder.class.getName() + "\n");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[0], original) {
            @Override public Enumeration<URL> getResources(String name) throws IOException {
                return name.equals("META-INF/services/" + ImageDecoder.class.getName())
                    ? Collections.enumeration(List.of(service.toUri().toURL())) : super.getResources(name);
            }
        }) {
            Thread.currentThread().setContextClassLoader(loader);
            AutoCloseable executor;
            Function<byte[], CompletableFuture<ConvertedImage>> convert;
            LongSupplier retained;
            if (jpeg) {
                var async = JpegConverter.create().async(1, 2, 100);
                executor = async; convert = async::convert; retained = async::retainedInputBytes;
            } else {
                var async = ImageConverter.builder().effort(0).build().async(1, 2, 100);
                executor = async; convert = async::convert; retained = async::retainedInputBytes;
            }
            try {
                byte[] bmp = {'B', 'M', 0, 0};
                var first = convert.apply(bmp);
                assertTrue(CountingDecoder.entered.await(10, TimeUnit.SECONDS));
                var skipped = convert.apply(bmp);
                if (timeout) assertTrue(skipped.completeExceptionally(new TimeoutException()));
                else assertTrue(skipped.cancel(false));
                var last = convert.apply(bmp);
                assertEquals(3L * bmp.length, retained.getAsLong());
                CountingDecoder.release.countDown();
                assertEquals(1, first.get(10, TimeUnit.SECONDS).width());
                assertEquals(1, last.get(10, TimeUnit.SECONDS).width());
                executor.close();
                assertEquals(2, CountingDecoder.calls.get(), "Completed queued work must not decode");
                assertEquals(0, retained.getAsLong());
                assertTrue(skipped.isCompletedExceptionally());
            } finally {
                CountingDecoder.release.countDown();
                executor.close();
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    public static final class CountingDecoder implements ImageDecoder {
        static final AtomicInteger calls = new AtomicInteger();
        static CountDownLatch entered, release;
        public CountingDecoder() {}
        @Override public Set<ImageFormat> formats() { return Set.of(ImageFormat.BMP); }
        @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits,
                                           FramePolicy frames, Arena arena) {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new ImageException("Test decoder timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new ImageException("Test decoder interrupted", error);
                }
            }
            MemorySegment pixels = arena.allocate(4, 4).fill((byte) 0xff);
            return new PixelImage(1, 1, 8, 1, 1, 1, 13, false, 4, pixels, MemorySegment.NULL);
        }
    }
}
