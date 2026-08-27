module no.beint.glimt.jpegli {
    requires transitive no.beint.glimt;
    provides no.beint.glimt.spi.JpegEncoder with no.beint.glimt.jpegli.JpegliEncoder;
}
