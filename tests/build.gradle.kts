dependencies {
    testImplementation(project(":all"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "1g"
}
