module no.beint.glimt.heic {
    requires transitive no.beint.glimt;
    provides no.beint.glimt.spi.ImageDecoder with no.beint.glimt.heic.HeicCodec;
}
