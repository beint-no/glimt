module no.beint.glimt.png {
    requires transitive no.beint.glimt;
    provides no.beint.glimt.spi.ImageDecoder with no.beint.glimt.png.PngCodec;
}
