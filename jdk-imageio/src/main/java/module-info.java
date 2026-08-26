module no.beint.glimt.imageio {
    requires transitive no.beint.glimt;
    requires java.desktop;
    requires java.xml;
    provides no.beint.glimt.spi.ImageDecoder with no.beint.glimt.imageio.JdkImageDecoder;
}
