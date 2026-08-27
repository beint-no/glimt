module no.beint.glimt.resize {
    requires no.beint.glimt;
    provides no.beint.glimt.spi.ImageResizer with no.beint.glimt.resize.StbImageResizer;
}
