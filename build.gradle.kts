import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction

abstract class VerifyPlatformVariants : DefaultTask() {
    @get:Classpath abstract val defaultClasspath: ConfigurableFileCollection
    @get:Classpath abstract val explicitDefaultClasspath: ConfigurableFileCollection
    @get:Classpath abstract val macosClasspath: ConfigurableFileCollection
    @get:Classpath abstract val portableClasspath: ConfigurableFileCollection
    @get:Classpath abstract val allGlibcClasspath: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        fun ConfigurableFileCollection.names(): Set<String> = files.mapTo(mutableSetOf(), File::getName)
        fun Set<String>.nativePlatforms(module: String): Set<String> = mapNotNullTo(mutableSetOf()) { filename ->
            Regex("^$module-(macos-arm64|linux-x64-glibc|linux-x64-musl)-").find(filename)?.groupValues?.get(1)
        }

        check(defaultClasspath.names().nativePlatforms("jpegli") == setOf("linux-x64-musl"))
        check(explicitDefaultClasspath.names().nativePlatforms("jpegli") == setOf("linux-x64-musl"))
        check(macosClasspath.names().nativePlatforms("jpegli") == setOf("macos-arm64"))
        check(portableClasspath.names().nativePlatforms("jpegli") ==
            setOf("macos-arm64", "linux-x64-glibc", "linux-x64-musl"))

        val glibcNames = allGlibcClasspath.names()
        for (codec in listOf("avif", "jpeg", "jpegli", "png", "webp", "heic", "jxl", "extra", "resize")) {
            check(glibcNames.nativePlatforms(codec) == setOf("linux-x64-glibc"))
        }
    }
}

plugins { id("com.vanniktech.maven.publish") version "0.37.0" apply false }
allprojects { group = "no.beint.glimt"; version = "0.5.0" }
tasks.register("printReleaseVersion") { val value = version.toString(); doLast { println(value) } }
val verifyDocumentation = tasks.register<Exec>("verifyDocumentation") {
    description = "Checks documentation links and consumer coordinates against the release version."
    commandLine("python3", "tools/verify-docs.py", version.toString())
}
val verifyNativeBuildTools = tasks.register<Exec>("verifyNativeBuildTools") {
    description = "Checks canonical native source hashing."
    commandLine("python3", "-m", "unittest", "discover", "-s", "native", "-p", "test_*.py")
}

val codecs = listOf("avif", "jpeg", "jpegli", "png", "webp", "heic", "jxl", "extra", "resize")
val platforms = listOf("macos-arm64", "linux-x64-glibc", "linux-x64-musl")
val defaultPlatform = "linux-x64-musl"
val selectablePlatforms = platforms.filterNot(defaultPlatform::equals) + "portable"
val buildPlatform = providers.gradleProperty("glimt.platform").orElse(provider {
    when {
        System.getProperty("os.name").startsWith("Mac") -> "macos-arm64"
        file("/lib/ld-musl-x86_64.so.1").exists() -> "linux-x64-musl"
        else -> "linux-x64-glibc"
    }
}).get()
check(buildPlatform in platforms) { "Unsupported Glimt build platform: $buildPlatform" }
extra["glimtPlatform"] = buildPlatform
val nativeSources = mapOf(
    "avif" to listOf("avif", "aom", "dav1d", "yuv"),
    "jpeg" to listOf("jpeg", "lcms"), "png" to listOf("png", "zlib", "lcms"),
    "jpegli" to listOf("jpegli", "jpegli-highway", "jpegli-libjpeg", "lcms"),
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
    tasks.withType<Javadoc>().configureEach {
        // Keep link, HTML and accessibility checks strict while allowing
        // implementation/provider members to inherit concise API prose.
        (options as org.gradle.external.javadoc.StandardJavadocDocletOptions).apply {
            addBooleanOption("Xdoclint:all,-missing", true)
            addStringOption("-show-module-contents", "api")
            addStringOption("-show-packages", "exported")
        }
    }
    tasks.named("check") { dependsOn(verifyDocumentation, verifyNativeBuildTools) }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    if (name != "core" && name != "tests" && name != "benchmarks" && name !in codecs.flatMap { codec -> platforms.map { "$codec-$it" } }) {
        dependencies.add("api", dependencies.project(mapOf("path" to ":core")))
    }
    fun Configuration.configureJavaVariant(usage: String, capability: String) {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 26)
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(usage))
        }
        outgoing.artifact(tasks.named("jar"))
        outgoing.capability(capability)
    }
    fun publishVariant(apiElements: Configuration, runtimeElements: Configuration) {
        val javaComponent = components.getByName("java") as AdhocComponentWithVariants
        javaComponent.addVariantsFromConfiguration(apiElements) {
            mapToMavenScope("compile")
            mapToOptional()
        }
        javaComponent.addVariantsFromConfiguration(runtimeElements) {
            mapToMavenScope("runtime")
            mapToOptional()
        }
    }
    fun ProjectDependency.requirePlatform(module: String, platform: String) {
        capabilities { requireCapability("${project.group}:$module-platform-$platform") }
    }
    if (name in codecs) {
        dependencies.add("runtimeOnly", dependencies.project(mapOf("path" to ":$name-$defaultPlatform")))
        for (elements in listOf("apiElements", "runtimeElements")) {
            configurations.named(elements) {
                outgoing.capability("$group:${project.name}:$version")
                outgoing.capability("$group:${project.name}-platform-$defaultPlatform:$version")
            }
        }
        for (platform in selectablePlatforms) {
            val variant = platform.split('-').mapIndexed { index, word ->
                if (index == 0) word else word.replaceFirstChar(Char::uppercase)
            }.joinToString("")
            val apiDependencies = configurations.create("${variant}ApiDependencies") {
                isCanBeConsumed = false
                isCanBeResolved = false
            }
            dependencies.add(apiDependencies.name, dependencies.project(mapOf("path" to ":core")))
            val runtimeDependencies = configurations.create("${variant}RuntimeDependencies") {
                isCanBeConsumed = false
                isCanBeResolved = false
                extendsFrom(apiDependencies)
            }
            val nativePlatforms = if (platform == "portable") platforms else listOf(platform)
            for (nativePlatform in nativePlatforms) {
                dependencies.add(
                    runtimeDependencies.name,
                    dependencies.project(mapOf("path" to ":$name-$nativePlatform")),
                )
            }
            val capability = "$group:$name-platform-$platform:$version"
            val apiElements = configurations.create("${variant}ApiElements") {
                extendsFrom(apiDependencies)
                configureJavaVariant(Usage.JAVA_API, capability)
            }
            val runtimeElements = configurations.create("${variant}RuntimeElements") {
                extendsFrom(runtimeDependencies)
                configureJavaVariant(Usage.JAVA_RUNTIME, capability)
            }
            publishVariant(apiElements, runtimeElements)
        }
    }
    if (name == "all") {
        for (module in codecs + "jdk-imageio") dependencies.add("api", dependencies.project(mapOf("path" to ":$module")))
        for (elements in listOf("apiElements", "runtimeElements")) {
            configurations.named(elements) {
                outgoing.capability("$group:${project.name}:$version")
                outgoing.capability("$group:${project.name}-platform-$defaultPlatform:$version")
            }
        }
        for (platform in selectablePlatforms) {
            val variant = platform.split('-').mapIndexed { index, word ->
                if (index == 0) word else word.replaceFirstChar(Char::uppercase)
            }.joinToString("")
            val dependencyBucket = configurations.create("${variant}Dependencies") {
                isCanBeConsumed = false
                isCanBeResolved = false
            }
            for (module in listOf("core", "jdk-imageio")) {
                dependencies.add(dependencyBucket.name, dependencies.project(mapOf("path" to ":$module")))
            }
            for (module in codecs) {
                val dependency = dependencies.project(mapOf("path" to ":$module")) as ProjectDependency
                dependency.requirePlatform(module, platform)
                dependencies.add(dependencyBucket.name, dependency)
            }
            val capability = "$group:$name-platform-$platform:$version"
            val apiElements = configurations.create("${variant}ApiElements") {
                extendsFrom(dependencyBucket)
                configureJavaVariant(Usage.JAVA_API, capability)
            }
            val runtimeElements = configurations.create("${variant}RuntimeElements") {
                extendsFrom(dependencyBucket)
                configureJavaVariant(Usage.JAVA_RUNTIME, capability)
            }
            publishVariant(apiElements, runtimeElements)
        }
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
                        /** Creates the service provider discovered by {@link java.util.ServiceLoader}. */
                        public Bundle() {}
                        /** {@inheritDoc} */
                        public String codec() { return "$codec"; }
                        /** {@inheritDoc} */
                        public String platform() { return "$platform"; }
                        /** {@inheritDoc} */
                        public java.io.InputStream open(String filename) {
                            return Bundle.class.getResourceAsStream("/no/beint/glimt/natives/$codec/$platform/" + filename);
                        }
                    }
                """.trimIndent() + "\n")
                folder.resolve("java/module-info.java").writeText("/** Native $codec resources for $platform. */\nmodule $moduleName { requires no.beint.glimt; provides no.beint.glimt.spi.NativeBundle with $moduleName.Bundle; }\n")
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
        if (name in codecs || name == "all") {
            extensions.configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    for (variant in listOf("apiElements", "runtimeElements") + selectablePlatforms.flatMap { platform ->
                        val name = platform.split('-').mapIndexed { index, word ->
                            if (index == 0) word else word.replaceFirstChar(Char::uppercase)
                        }.joinToString("")
                        listOf("${name}ApiElements", "${name}RuntimeElements")
                    }) {
                        suppressPomMetadataWarningsFor(variant)
                    }
                }
            }
        }
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
            if (project.name.contains("-linux-")) {
                // Only Linux bundles statically link GCC runtime components.
                from(rootProject.file("native/licenses")) { into("native/licenses") }
            }
            from(rootProject.file("native/patches")) {
                for (source in nativeSources.getValue(codec)) include("$source/**")
                into("native/patches")
            }
            from(listOf(rootProject.file("native/build.py"), rootProject.file("native/source_hash.py"),
                rootProject.file("native/sources.json"))) { into("native") }
            // LGPL corresponding source is shipped with HEIC. Other bundles
            // include the pinned build recipe, avoiding redistribution of
            // unrelated upstream test photographs under separate licenses.
            for (source in if (codec == "heic") nativeSources.getValue(codec) else emptyList()) {
                from(rootProject.file("native/.work/archives/$source.tar.gz")) { into("native/.work/archives") }
            }
        }
        if (codec != null) {
            val sourceArchive = layout.buildDirectory.file("libs/${project.name}-${project.version}-sources.jar").get().asFile
            val expectsGccLicenses = project.name.contains("-linux-")
            val verifySourceLicenseScope = tasks.register("verifySourceLicenseScope") {
                description = "Checks that Linux-only GCC license texts have the correct source-JAR scope."
                dependsOn("sourcesJar")
                inputs.file(sourceArchive)
                doLast {
                    java.util.zip.ZipFile(sourceArchive).use { archive ->
                        val gccLicenses = listOf("native/licenses/COPYING3", "native/licenses/COPYING.RUNTIME")
                        val present = gccLicenses.map { archive.getEntry(it) != null }
                        check(present.all { it } == expectsGccLicenses && present.distinct().size == 1) {
                            "GCC license scope mismatch in ${sourceArchive.name}"
                        }
                    }
                }
            }
            tasks.named("check") { dependsOn(verifySourceLicenseScope) }
        }
        tasks.matching { it.name.startsWith("publish") && !it.name.contains("MavenLocal") }.configureEach {
            dependsOn(verifyNativeRelease)
        }
    }
}

fun platformVerificationConfiguration(
    configurationName: String,
    module: String,
    platform: String? = null,
): Configuration = configurations.create(configurationName) {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 26)
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
    val dependency = project.dependencies.project(mapOf("path" to ":$module")) as ProjectDependency
    if (platform != null) {
        dependency.capabilities { requireCapability("$group:$module-platform-$platform") }
    }
    project.dependencies.add(name, dependency)
}

val platformVerifications = mapOf(
    "default" to platformVerificationConfiguration("verifyDefaultPlatform", "jpegli"),
    "linux-x64-musl" to platformVerificationConfiguration(
        "verifyExplicitDefaultPlatform",
        "jpegli",
        "linux-x64-musl",
    ),
    "macos-arm64" to platformVerificationConfiguration("verifyMacosPlatform", "jpegli", "macos-arm64"),
    "portable" to platformVerificationConfiguration("verifyPortablePlatform", "jpegli", "portable"),
    "all-linux-x64-glibc" to platformVerificationConfiguration(
        "verifyAllLinuxGlibcPlatform",
        "all",
        "linux-x64-glibc",
    ),
)
val verifyPlatformVariants = tasks.register<VerifyPlatformVariants>("verifyPlatformVariants") {
    description = "Checks that Glimt resolves only the default or explicitly selected native platforms."
    defaultClasspath.from(platformVerifications.getValue("default"))
    explicitDefaultClasspath.from(platformVerifications.getValue("linux-x64-musl"))
    macosClasspath.from(platformVerifications.getValue("macos-arm64"))
    portableClasspath.from(platformVerifications.getValue("portable"))
    allGlibcClasspath.from(platformVerifications.getValue("all-linux-x64-glibc"))
}
subprojects {
    tasks.named("check") { dependsOn(verifyPlatformVariants) }
}
