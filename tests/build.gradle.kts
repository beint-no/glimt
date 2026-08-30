val glimtPlatform = rootProject.extra["glimtPlatform"] as String

dependencies {
    testImplementation(project(":all")) {
        capabilities { requireCapability("no.beint.glimt:all-platform-$glimtPlatform") }
    }
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "1g"
}
