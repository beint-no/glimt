package no.beint.glimt.internal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import no.beint.glimt.ImageException;
import no.beint.glimt.spi.NativeBundle;

/** Loads only explicitly selected or checksum-verified bundled libraries. */
@SuppressWarnings("restricted") // Deliberate FFM boundary; native access is granted by the application.
public final class NativeLibrary {
    private static final ConcurrentHashMap<String, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();
    private static final Arena LIBRARY_LIFETIME = Arena.ofAuto();
    private NativeLibrary() {}
    public static SymbolLookup load(String codec) { return LIBRARIES.computeIfAbsent(codec, NativeLibrary::extractAndLoad); }
    public static String platform() {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) throw new ImageException("Glimt requires a little-endian platform");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new ImageException("Unsupported native architecture: " + System.getProperty("os.arch"));
        };
        if (os.contains("mac")) return "macos-" + arch;
        if (os.contains("linux")) {
            String loaderArch = arch.equals("x64") ? "x86_64" : "aarch64";
            boolean musl = usesMusl(loaderArch);
            return "linux-" + arch + (musl ? "-musl" : "-glibc");
        }
        throw new ImageException("No published Glimt natives for " + os + "/" + arch);
    }
    private static boolean usesMusl(String arch) {
        // A glibc machine may also have musl-tools installed. Select the libc
        // actually mapped into this JVM, rather than whichever loaders exist.
        try {
            String maps = Files.readString(Path.of("/proc/self/maps"));
            if (maps.contains("/ld-musl-") || maps.contains("/libc.musl-")) return true;
            if (maps.contains("/libc.so.6") || java.util.regex.Pattern.compile("/libc-[0-9.]+\\.so\\b").matcher(maps).find()) return false;
        } catch (IOException | SecurityException unavailable) {
            // Restricted procfs: retain loader detection for supported minimal containers.
        }
        return Files.exists(Path.of("/lib/ld-musl-" + arch + ".so.1")) ||
            Files.exists(Path.of("/usr/lib/ld-musl-" + arch + ".so.1"));
    }
    private static SymbolLookup extractAndLoad(String codec) {
        String suffix = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac") ? ".dylib" : ".so";
        String filename = "libglimt_" + codec + suffix;
        String override = System.getProperty("glimt.native." + codec);
        if (override != null) {
            Path library = Path.of(override).toAbsolutePath();
            if (!Files.isRegularFile(library)) throw new ImageException("Native override is not a file: " + library);
            return SymbolLookup.libraryLookup(library, LIBRARY_LIFETIME);
        }
        String target = platform();
        NativeBundle bundle = ServiceLoader.load(NativeBundle.class).stream().map(ServiceLoader.Provider::get)
            .filter(candidate -> candidate.codec().equals(codec) && candidate.platform().equals(target)).findFirst()
            .orElseThrow(() -> new ImageException("Missing " + codec + " natives for " + target + ". Add no.beint.glimt:" + codec + "-" + target));
        try (InputStream manifest = bundle.open("manifest.properties")) {
            if (manifest == null) throw new ImageException("Missing " + codec + " natives for " + target +
                ". Add no.beint.glimt:" + codec + "-" + target + " at the same Glimt version.");
            Properties hashes = new Properties(); hashes.load(manifest);
            if (!hashes.containsKey(filename)) throw new ImageException("Native manifest omits " + filename);
            Path parent = Path.of(System.getProperty("glimt.native.cache", System.getProperty("java.io.tmpdir")));
            // A private per-process directory prevents shared-cache substitution and symlink races.
            Path directory = Files.createTempDirectory(parent, "glimt-" + codec + "-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            directory.toFile().deleteOnExit();
            for (String name : hashes.stringPropertyNames().stream().sorted().toList()) {
                if (!name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals("..")) throw new ImageException("Invalid native resource name");
                Path output = directory.resolve(name);
                try (InputStream resource = bundle.open(name)) {
                    if (resource == null) throw new ImageException("Missing native resource: " + name);
                    Files.copy(resource, output);
                }
                if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) throw new ImageException("Unsafe native extraction target");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream stream = Files.newInputStream(output)) {
                    byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equals(hashes.getProperty(name))) throw new ImageException("Native SHA-256 mismatch: " + name);
                output.toFile().deleteOnExit();
            }
            return SymbolLookup.libraryLookup(directory.resolve(filename), LIBRARY_LIFETIME);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ImageException("Cannot load bundled " + codec + " for " + target, exception);
        } catch (IllegalCallerException exception) {
            throw new ImageException("Enable native access for no.beint.glimt (module path) or ALL-UNNAMED (class path)", exception);
        } catch (UnsatisfiedLinkError exception) {
            throw new ImageException("Cannot link bundled " + codec + " for " + target + "; verify the supported OS baseline", exception);
        }
    }
}
