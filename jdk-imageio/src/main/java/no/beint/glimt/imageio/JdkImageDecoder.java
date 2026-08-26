package no.beint.glimt.imageio;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStreamImpl;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;
import org.w3c.dom.Node;

/** Optional legacy-format reader using only JDK providers. Normalizes output to 8-bit sRGB. */
public final class JdkImageDecoder implements ImageDecoder {
    public JdkImageDecoder() {}
    @Override public Set<ImageFormat> formats() { return Set.of(ImageFormat.GIF, ImageFormat.BMP, ImageFormat.TIFF, ImageFormat.WBMP); }
    @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena) {
        String name = format.name();
        ImageReader reader = null;
        var candidates = ImageIO.getImageReadersByFormatName(name);
        while (candidates.hasNext()) {
            ImageReader candidate = candidates.next();
            if ("java.desktop".equals(candidate.getClass().getModule().getName())) { reader = candidate; break; }
            candidate.dispose();
        }
        if (reader == null) throw new ImageException("No JDK reader for " + format);
        try (SegmentInput stream = new SegmentInput(input)) {
            reader.setInput(stream, false, false);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            limits.checkDimensions(width, height, 8); // account for BufferedImage plus native RGBA
            int count = reader.getNumImages(true);
            if (count < 1 || count > limits.maxFrames() || count > 1 && frames == FramePolicy.REJECT)
                throw new ImageException("Multi-frame input rejected by frame policy");
            BufferedImage image = reader.read(0);
            int left = 0, top = 0, background = 0;
            if (format == ImageFormat.GIF) {
                Node streamMetadata = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
                Node frameMetadata = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
                Node screen = find(streamMetadata, "LogicalScreenDescriptor");
                if (screen != null) { width = attribute(screen, "logicalScreenWidth"); height = attribute(screen, "logicalScreenHeight"); }
                limits.checkDimensions(width, height, 8);
                Node descriptor = find(frameMetadata, "ImageDescriptor");
                if (descriptor != null) { left = attribute(descriptor, "imageLeftPosition"); top = attribute(descriptor, "imageTopPosition"); }
                if (left < 0 || top < 0 || image.getWidth() > width - left || image.getHeight() > height - top)
                    throw new ImageException("GIF frame exceeds logical screen");
                Node control = find(frameMetadata, "GraphicControlExtension");
                boolean transparent = control != null && Boolean.parseBoolean(control.getAttributes().getNamedItem("transparentColorFlag").getNodeValue());
                Node table = find(streamMetadata, "GlobalColorTable");
                if (!transparent && table != null) {
                    int index = attribute(table, "backgroundColorIndex");
                    for (Node color = table.getFirstChild(); color != null; color = color.getNextSibling()) {
                        if (color.getNodeName().equals("ColorTableEntry") && attribute(color, "index") == index) {
                            background = 0xff000000 | attribute(color, "red") << 16 | attribute(color, "green") << 8 | attribute(color, "blue");
                            break;
                        }
                    }
                }
            }
            long stride = (long)width * 4;
            MemorySegment pixels = arena.allocate(stride * height, 4);
            if (background != 0) for (long offset = 0; offset < pixels.byteSize(); offset += 4) putArgb(pixels, offset, background);
            int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, row.length, 1, row, 0, row.length);
                for (int x = 0; x < row.length; x++) {
                    long offset = (y + (long)top) * stride + (x + (long)left) * 4;
                    putArgb(pixels, offset, row[x]);
                }
            }
            image.flush();
            int orientation = 1;
            var metadata = reader.getImageMetadata(0);
            if (format == ImageFormat.TIFF && metadata != null && metadata.isStandardMetadataFormatSupported()) {
                Node value = find(metadata.getAsTree("javax_imageio_1.0"), "ImageOrientation");
                if (value != null) orientation = switch (value.getAttributes().getNamedItem("value").getNodeValue()) {
                    case "FlipH" -> 2; case "Rotate180" -> 3; case "FlipV" -> 4;
                    case "FlipHRotate90" -> 5; case "Rotate270" -> 6;
                    case "FlipVRotate90" -> 7; case "Rotate90" -> 8; default -> 1;
                };
            }
            return new PixelImage(width, height, 8, count, orientation, 1, 13, stride, pixels, MemorySegment.NULL);
        } catch (IOException | IndexOutOfBoundsException exception) {
            throw new ImageException("Cannot decode " + format, exception);
        } finally { reader.dispose(); }
    }
    private static void putArgb(MemorySegment pixels, long offset, int argb) {
        pixels.set(ValueLayout.JAVA_BYTE, offset, (byte)(argb >>> 16));
        pixels.set(ValueLayout.JAVA_BYTE, offset + 1, (byte)(argb >>> 8));
        pixels.set(ValueLayout.JAVA_BYTE, offset + 2, (byte)argb);
        pixels.set(ValueLayout.JAVA_BYTE, offset + 3, (byte)(argb >>> 24));
    }
    private static Node find(Node node, String name) {
        if (node.getNodeName().equals(name)) return node;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node result = find(child, name); if (result != null) return result;
        }
        return null;
    }
    private static int attribute(Node node, String name) { return Integer.parseInt(node.getAttributes().getNamedItem(name).getNodeValue()); }
    private static final class SegmentInput extends ImageInputStreamImpl {
        private final MemorySegment bytes;
        SegmentInput(MemorySegment bytes) { this.bytes = bytes; }
        @Override public int read() throws IOException {
            checkClosed(); bitOffset = 0;
            return streamPos >= bytes.byteSize() ? -1 : bytes.get(ValueLayout.JAVA_BYTE, streamPos++) & 255;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            checkClosed(); java.util.Objects.checkFromIndexSize(offset, length, target.length); bitOffset = 0;
            if (length == 0) return 0;
            if (streamPos >= bytes.byteSize()) return -1;
            int count = (int)Math.min(length, bytes.byteSize() - streamPos);
            MemorySegment.copy(bytes, ValueLayout.JAVA_BYTE, streamPos, target, offset, count);
            streamPos += count; return count;
        }
        @Override public long length() { return bytes.byteSize(); }
    }
}
