package no.beint.glimt.spi;

import java.io.IOException;
import java.io.InputStream;

/** Resource-only native distribution SPI. JPMS service binding resolves installed platform modules. */
public interface NativeBundle {
    String codec();
    String platform();
    /** Opens a resource in this bundle; the caller closes the stream. */
    InputStream open(String filename) throws IOException;
}
