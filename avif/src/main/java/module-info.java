module no.beint.glimt.avif {
    requires transitive no.beint.glimt;
    provides no.beint.glimt.spi.ImageDecoder with no.beint.glimt.avif.AvifCodec;
    provides no.beint.glimt.spi.AvifEncoder with no.beint.glimt.avif.AvifCodec;
}
