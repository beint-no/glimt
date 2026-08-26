pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "glimt"
include("core", "avif", "jpeg", "png", "webp", "heic", "jxl", "extra", "jdk-imageio", "all", "tests")
for (codec in listOf("avif", "jpeg", "png", "webp", "heic", "jxl", "extra")) {
    for (platform in listOf("macos-arm64", "linux-x64-glibc", "linux-x64-musl")) {
        include("$codec-$platform")
        project(":$codec-$platform").projectDir = file("natives/$codec-$platform")
    }
}
