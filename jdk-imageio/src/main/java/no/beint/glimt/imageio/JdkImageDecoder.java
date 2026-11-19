package no.beint.glimt.imageio;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStreamImpl;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;
import org.w3c.dom.Node;

/** Optional legacy-format reader using only JDK providers. Normalizes output to 8-bit sRGB. */
public final class JdkImageDecoder implements ImageDecoder {
    private static final ValueLayout.OfInt RGBA = ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN);
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
        if (reader == null) throw new ImageException(ImageException.Reason.MISSING_CODEC, "No JDK reader for " + format);
        try (SegmentInput stream = new SegmentInput(input)) {
            reader.setInput(stream, false, false);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            limits.checkDimensions(width, height, 8); // account for BufferedImage plus native RGBA
            int count = reader.getNumImages(true);
            if (count < 1) throw new ImageException(ImageException.Reason.INVALID_INPUT, "Image contains no frames");
            if (count > limits.maxFrames()) throw new ImageException(ImageException.Reason.LIMITS_EXCEEDED, "Image frame count exceeds configured limit");
            if (count > 1 && frames == FramePolicy.REJECT)
                throw new ImageException(ImageException.Reason.MULTI_FRAME_REJECTED, "Multi-frame input rejected by frame policy");
            BufferedImage image = reader.read(0);
            if (image == null) throw new ImageException(ImageException.Reason.INVALID_INPUT, "Cannot decode " + format);
            boolean hasAlpha = image.getColorModel().hasAlpha();
            int left = 0, top = 0, background = 0;
            if (format == ImageFormat.GIF) {
                var rawStreamMetadata = reader.getStreamMetadata();
                Node streamMetadata = rawStreamMetadata == null ? null : rawStreamMetadata.getAsTree("javax_imageio_gif_stream_1.0");
                var rawFrameMetadata = reader.getImageMetadata(0);
                Node frameMetadata = rawFrameMetadata == null ? null : rawFrameMetadata.getAsTree("javax_imageio_gif_image_1.0");
                Node screen = find(streamMetadata, "LogicalScreenDescriptor");
                if (screen != null) {
                    width = attribute(screen, "logicalScreenWidth", width);
                    height = attribute(screen, "logicalScreenHeight", height);
                }
                limits.checkDimensions(width, height, 8);
                Node descriptor = find(frameMetadata, "ImageDescriptor");
                if (descriptor != null) {
                    left = attribute(descriptor, "imageLeftPosition", 0);
                    top = attribute(descriptor, "imageTopPosition", 0);
                }
                if (left < 0 || top < 0 || image.getWidth() > width - left || image.getHeight() > height - top)
                    throw new ImageException(ImageException.Reason.INVALID_INPUT, "GIF frame exceeds logical screen");
                Node control = find(frameMetadata, "GraphicControlExtension");
                boolean transparent = Boolean.parseBoolean(attributeText(control, "transparentColorFlag"));
                Node table = find(streamMetadata, "GlobalColorTable");
                if (!transparent && table != null) {
                    Integer index = optionalAttribute(table, "backgroundColorIndex");
                    if (index != null) {
                        for (Node color = table.getFirstChild(); color != null; color = color.getNextSibling()) {
                            if (color.getNodeName().equals("ColorTableEntry") && index.equals(optionalAttribute(color, "index"))) {
                                Integer red = optionalAttribute(color, "red");
                                Integer green = optionalAttribute(color, "green");
                                Integer blue = optionalAttribute(color, "blue");
                                if (red != null && green != null && blue != null) {
                                    background = 0xff000000 | red << 16 | green << 8 | blue;
                                }
                                break;
                            }
                        }
                    }
                }
                hasAlpha |= background == 0 &&
                    (left != 0 || top != 0 || image.getWidth() != width || image.getHeight() != height);
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
                String orientationValue = attributeText(value, "value");
                if (orientationValue != null) orientation = switch (orientationValue) {
                    case "FlipH" -> 2; case "Rotate180" -> 3; case "FlipV" -> 4;
                    case "FlipHRotate90" -> 5; case "Rotate270" -> 6;
                    case "FlipVRotate90" -> 7; case "Rotate90" -> 8; default -> 1;
                };
            }
            return new PixelImage(width, height, 8, count, orientation, 1, 13,
                hasAlpha, stride, pixels, MemorySegment.NULL);
        } catch (ImageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ImageException(ImageException.Reason.INVALID_INPUT, "Cannot decode " + format, exception);
        } finally { reader.dispose(); }
    }
    private static void putArgb(MemorySegment pixels, long offset, int argb) {
        pixels.set(RGBA, offset, Integer.rotateLeft(argb, 8));
    }
    private static Node find(Node node, String name) {
        if (node == null) return null;
        if (node.getNodeName().equals(name)) return node;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node result = find(child, name); if (result != null) return result;
        }
        return null;
    }
    private static String attributeText(Node node, String name) {
        if (node == null || node.getAttributes() == null) return null;
        Node item = node.getAttributes().getNamedItem(name);
        return item == null ? null : item.getNodeValue();
    }
    private static Integer optionalAttribute(Node node, String name) {
        String text = attributeText(node, name);
        return text == null ? null : Integer.valueOf(text);
    }
    private static int attribute(Node node, String name, int fallback) {
        Integer value = optionalAttribute(node, name);
        return value == null ? fallback : value;
    }
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
