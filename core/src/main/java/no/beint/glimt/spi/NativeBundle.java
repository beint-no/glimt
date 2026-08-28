package no.beint.glimt.spi;

import java.io.IOException;
import java.io.InputStream;

/** Resource-only native distribution SPI. JPMS service binding resolves installed platform modules. */
public interface NativeBundle {
    /**
     * Identifies bundle contents.
     * @return codec identifier used by the native loader
     */
    String codec();
    /**
     * Identifies the target runtime.
     * @return normalized operating-system, architecture and libc identifier
     */
    String platform();
    /**
     * Opens a resource in this bundle; the caller closes the stream.
     *
     * @param filename native resource filename
     * @return the resource stream, or {@code null} when absent
     * @throws IOException when the resource cannot be opened
     */
    InputStream open(String filename) throws IOException;
}
