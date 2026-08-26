module no.beint.glimt.extra {
    requires transitive no.beint.glimt;
    provides no.beint.glimt.spi.ImageDecoder with no.beint.glimt.extra.ExtraCodec;
}
