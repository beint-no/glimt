package no.beint.glimt.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import no.beint.glimt.*;

public final class Main {
    public static void main(String[] args) throws Exception {
        var converter = ImageConverter.builder().effort(0).build();
        var expected = Set.of(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.HEIC, ImageFormat.AVIF);
        if (!converter.supportedFormats().equals(expected)) throw new AssertionError(converter.supportedFormats());
        Path input = Path.of(args[0]);
        for (String filename : new String[]{"baseline.jpg", "rgba.png", "lossless.webp", "rgba.heic"}) {
            var result = converter.convert(input.resolve(filename));
            if (result.width() != 97 || result.height() != 73 || ImageFormat.detect(result.bytes()) != ImageFormat.AVIF)
                throw new AssertionError(filename);
            var roundtrip = converter.convert(result.bytes());
            if (roundtrip.width() != result.width() || roundtrip.height() != result.height()) throw new AssertionError(filename);
            if (args.length > 1) { Path out = Path.of(args[1]); Files.createDirectories(out); Files.write(out.resolve(filename + ".avif"), result.bytes()); }
        }
        System.out.println("Glimt runtime smoke passed: " + expected);
    }
}
