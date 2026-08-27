import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins { id("com.vanniktech.maven.publish") version "0.37.0" apply false }
allprojects { group = "no.beint.glimt"; version = "0.2.0" }
tasks.register("printReleaseVersion") { val value = version.toString(); doLast { println(value) } }

val codecs = listOf("avif", "jpeg", "png", "webp", "heic", "jxl", "extra", "resize")
val platforms = listOf("macos-arm64", "linux-x64-glibc", "linux-x64-musl")
val nativeSources = mapOf(
    "avif" to listOf("avif", "aom", "dav1d", "yuv"),
    "jpeg" to listOf("jpeg", "lcms"), "png" to listOf("png", "zlib", "lcms"),
    "webp" to listOf("webp"), "heic" to listOf("heif", "de265"),
    "jxl" to listOf("jxl", "highway", "brotli", "lcms"),
    "extra" to listOf("magick", "png", "zlib", "lcms"), "resize" to listOf("stb"),
)
val verifyNativeRelease = tasks.register<Exec>("verifyNativeRelease") {
    description = "Refuse publishing incomplete native distributions or missing corresponding sources."
    commandLine("python3", "tools/verify-release.py")
}
subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(26))
        modularity.inferModulePath.set(true)
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(26)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    if (name != "core" && name != "tests" && name != "benchmarks" && name !in codecs.flatMap { codec -> platforms.map { "$codec-$it" } }) {
        dependencies.add("api", dependencies.project(mapOf("path" to ":core")))
    }
    if (name in codecs) {
        for (platform in platforms) dependencies.add("runtimeOnly", dependencies.project(mapOf("path" to ":$name-$platform")))
    }
    if (name == "all") {
        for (module in codecs + "jdk-imageio") dependencies.add("api", dependencies.project(mapOf("path" to ":$module")))
    }
    val codec = codecs.firstOrNull { name.startsWith("$it-") }
    if (codec != null) {
        val platform = name.removePrefix("$codec-")
        dependencies.add("implementation", dependencies.project(mapOf("path" to ":core")))
        val moduleName = "no.beint.glimt.natives." + name.replace('-', '.').replace("arm64", "arm64bit").replace("x64", "x64bit")
        val generated = layout.buildDirectory.dir("generated/nativeBundle")
        val generateBundle = tasks.register("generateNativeBundle") {
            inputs.property("codec", codec); inputs.property("platform", platform)
            inputs.property("moduleName", moduleName)
            outputs.dir(generated)
            doLast {
                val folder = generated.get().asFile
                folder.deleteRecursively()
                val source = folder.resolve("java/" + moduleName.replace('.', '/') + "/Bundle.java")
                source.parentFile.mkdirs()
                source.writeText("""
                    package $moduleName;
                    /** Bundled $codec native resources for $platform. */
                    public final class Bundle implements no.beint.glimt.spi.NativeBundle {
                        public Bundle() {}
                        public String codec() { return "$codec"; }
                        public String platform() { return "$platform"; }
                        public java.io.InputStream open(String filename) {
                            return Bundle.class.getResourceAsStream("/no/beint/glimt/natives/$codec/$platform/" + filename);
                        }
                    }
                """.trimIndent() + "\n")
                folder.resolve("java/module-info.java").writeText("module $moduleName { requires no.beint.glimt; provides no.beint.glimt.spi.NativeBundle with $moduleName.Bundle; }\n")
                val service = folder.resolve("resources/META-INF/services/no.beint.glimt.spi.NativeBundle")
                service.parentFile.mkdirs(); service.writeText("$moduleName.Bundle\n")
            }
        }
        extensions.configure<SourceSetContainer> {
            named("main") {
                java.srcDir(generateBundle.map { generated.get().dir("java") })
                resources.srcDir(generateBundle.map { generated.get().dir("resources") })
            }
        }
        tasks.named<ProcessResources>("processResources") {
            from(rootProject.file("native/dist/$platform/$codec")) {
                into("no/beint/glimt/natives/$codec/$platform")
            }
        }
        tasks.named<Jar>("jar") {
            manifest.attributes["Automatic-Module-Name"] = moduleName
        }
    }
    if (name != "tests" && name != "benchmarks") {
        apply(plugin = "com.vanniktech.maven.publish")
        extensions.configure<MavenPublishBaseExtension>("mavenPublishing") {
            publishToMavenCentral()
            signAllPublications()
            configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
            pom {
                name.set("Glimt ${project.name}")
                description.set("Modular JDK 26 image conversion with bundled native codecs and no third-party Java runtime dependencies.")
                inceptionYear.set("2026")
                url.set("https://github.com/beint-no/glimt")
                licenses {
                    license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") }
                    if (codec != null) license {
                        name.set(if (codec == "heic") "LGPL-3.0-or-later (bundled libheif and libde265)" else "Bundled upstream native licenses")
                        url.set("https://github.com/beint-no/glimt/blob/main/docs/native-licenses.md")
                    }
                }
                developers { developer { id.set("beint-no"); name.set("Beint"); url.set("https://github.com/beint-no") } }
                scm {
                    url.set("https://github.com/beint-no/glimt")
                    connection.set("scm:git:https://github.com/beint-no/glimt.git")
                    developerConnection.set("scm:git:ssh://git@github.com/beint-no/glimt.git")
                }
            }
        }
        if (codec != null) tasks.named<Jar>("sourcesJar") {
            from(rootProject.file("native/src")) { into("native/src") }
            from(rootProject.file("native/licenses")) { into("native/licenses") }
            from(rootProject.file("native/patches")) {
                for (source in nativeSources.getValue(codec)) include("$source/**")
                into("native/patches")
            }
            from(listOf(rootProject.file("native/build.py"), rootProject.file("native/sources.json"))) { into("native") }
            // LGPL corresponding source is shipped with HEIC. Other bundles
            // include the pinned build recipe, avoiding redistribution of
            // unrelated upstream test photographs under separate licenses.
            for (source in if (codec == "heic") nativeSources.getValue(codec) else emptyList()) {
                from(rootProject.file("native/.work/archives/$source.tar.gz")) { into("native/.work/archives") }
            }
        }
        tasks.matching { it.name.startsWith("publish") && !it.name.contains("MavenLocal") }.configureEach {
            dependsOn(verifyNativeRelease)
        }
    }
}
