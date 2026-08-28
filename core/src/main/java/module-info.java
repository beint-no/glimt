/**
 * Glimt's immutable conversion API and service-provider interfaces.
 */
module no.beint.glimt {
    exports no.beint.glimt;
    exports no.beint.glimt.spi;
    uses no.beint.glimt.spi.ImageDecoder;
    uses no.beint.glimt.spi.AvifEncoder;
    uses no.beint.glimt.spi.JpegEncoder;
    uses no.beint.glimt.spi.ImageResizer;
    uses no.beint.glimt.spi.NativeBundle;
}
