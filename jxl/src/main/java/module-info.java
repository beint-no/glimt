module no.beint.glimt.jxl {
    requires transitive no.beint.glimt;
    provides no.beint.glimt.spi.ImageDecoder with no.beint.glimt.jxl.JxlCodec;
}
