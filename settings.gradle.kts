pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "glimt"
include("core", "avif", "jpeg", "jpegli", "png", "webp", "heic", "jxl", "extra", "resize", "jdk-imageio", "all", "tests", "benchmarks")
for (codec in listOf("avif", "jpeg", "jpegli", "png", "webp", "heic", "jxl", "extra", "resize")) {
    for (platform in listOf("macos-arm64", "linux-x64-glibc", "linux-x64-musl")) {
        include("$codec-$platform")
        project(":$codec-$platform").projectDir = file("natives/$codec-$platform")
    }
}
