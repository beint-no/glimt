module no.beint.glimt.webp {
    requires transitive no.beint.glimt;
    provides no.beint.glimt.spi.ImageDecoder with no.beint.glimt.webp.WebpCodec;
}
