module no.beint.glimt.jpeg {
    requires transitive no.beint.glimt;
    provides no.beint.glimt.spi.ImageDecoder with no.beint.glimt.jpeg.JpegCodec;
}
