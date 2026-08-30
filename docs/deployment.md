# Deployment

## Normal applications

Add a Glimt codec or `all`, enable native access, and deploy the application as
usual. Codec modules include the Linux x64 musl resource JAR used by ReAI's
production image by default. Glimt extracts the matching bundle to a private
checksum-verified directory and loads it through FFM.

Consumers do not install libavif, JPEGli, ImageMagick or command-line tools, set
a native library path, copy Glimt's Dockerfiles, or download code at startup.
Only this JVM option is required on the classpath:

```text
--enable-native-access=ALL-UNNAMED
```

On the module path grant access to `no.beint.glimt` instead. Spring Boot's normal
executable JAR packaging retains the service descriptors and native resource
JARs. A custom shaded JAR must merge `META-INF/services` entries.

For an executable classpath JAR, the application can record the permission in
its main manifest instead of supplying a launcher argument. Configuring every
`Jar` task also covers Spring Boot's `bootJar`:

```kotlin
tasks.withType<Jar>().configureEach {
    manifest.attributes["Enable-Native-Access"] = "ALL-UNNAMED"
}
```

Only the manifest of the executable application JAR grants this permission; a
dependency cannot grant native access to the application's unnamed module.

## Containers

Glimt works in glibc and musl containers without extra OS packages. Its own
`native/Dockerfile.*` and `tools/Dockerfile.*` files build and verify release
artifacts; they are not templates for consumer applications.

The glibc libraries are built against glibc 2.35 so they continue to run on
Ubuntu 22.04 and newer distributions. CI executes the same binaries in clean
Ubuntu 22.04 and Ubuntu 26.04 images. Building on the newest Ubuntu would raise
the minimum glibc version without improving behavior on that release.

The musl libraries target musl 1.2.5 and are tested in clean hardened musl
images. Detection inspects the libc mapped into the JVM, so installing musl tools
on a glibc host does not cause the wrong bundle to be selected.

The extraction parent defaults to `java.io.tmpdir`. It must be writable and
permit native library loading. Use an existing private writable parent when a
container mounts `/tmp` with `noexec`:

```text
-Dglimt.native.cache=/opt/application/native-cache
```

Glimt creates a new mode-0700 process directory beneath that parent and verifies
every extracted file before loading it.

## Selecting another platform

The ordinary coordinates intentionally produce the ReAI deployment default:
Linux x64 musl. A Gradle consumer targeting macOS ARM64 or Linux x64 glibc asks
for that module's platform capability. The selected variant replaces the
default, so there are no exclusions or filename filters:

```kotlin
dependencies {
    implementation("no.beint.glimt:all:0.5.0") {
        capabilities {
            requireCapability("no.beint.glimt:all-platform-macos-arm64")
        }
    }
}
```

Selective codec bundles use the same convention on every Glimt dependency:

```kotlin
val glimt = "0.5.0"
dependencies {
    implementation("no.beint.glimt:jpegli:$glimt") {
        capabilities {
            requireCapability("no.beint.glimt:jpegli-platform-linux-x64-glibc")
        }
    }
    runtimeOnly("no.beint.glimt:jpeg:$glimt") {
        capabilities {
            requireCapability("no.beint.glimt:jpeg-platform-linux-x64-glibc")
        }
    }
}
```

Use `macos-arm64`, `linux-x64-glibc`, or `linux-x64-musl` in the capability name.
Choose `portable` to include all published platforms in one artifact, for example
`no.beint.glimt:all-platform-portable`. A missing bundle fails with an error
naming the exact artifact required for the detected runtime.
