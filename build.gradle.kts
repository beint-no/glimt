import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins { id("com.vanniktech.maven.publish") version "0.37.0" apply false }
allprojects { group = "no.beint.glimt"; version = "0.1.0" }
tasks.register("printReleaseVersion") { val value = version.toString(); doLast { println(value) } }

val codecs = listOf("avif", "jpeg", "png", "webp", "heic")
val platforms = listOf("macos-arm64", "linux-x64-glibc", "linux-x64-musl")
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
    if (name != "core" && name != "tests" && name !in codecs.flatMap { codec -> platforms.map { "$codec-$it" } }) {
        dependencies.add("api", project(":core"))
    }
    if (name in codecs) {
        for (platform in platforms) dependencies.add("runtimeOnly", project(":$name-$platform"))
    }
    if (name == "all") {
        for (module in codecs + "jdk-imageio") dependencies.add("api", project(":$module"))
    }
    val codec = codecs.firstOrNull { name.startsWith("$it-") }
    if (codec != null) {
        val platform = name.removePrefix("$codec-")
        tasks.named<ProcessResources>("processResources") {
            from(rootProject.file("native/dist/$platform/$codec")) {
                into("no/beint/glimt/natives/$codec/$platform")
            }
        }
        tasks.named<Jar>("jar") {
            manifest.attributes["Automatic-Module-Name"] = "no.beint.glimt.natives." + name.replace('-', '.')
        }
    }
    if (name != "tests") {
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
                licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
                developers { developer { id.set("beint-no"); name.set("Beint"); url.set("https://github.com/beint-no") } }
                scm {
                    url.set("https://github.com/beint-no/glimt")
                    connection.set("scm:git:https://github.com/beint-no/glimt.git")
                    developerConnection.set("scm:git:ssh://git@github.com/beint-no/glimt.git")
                }
            }
        }
    }
}
