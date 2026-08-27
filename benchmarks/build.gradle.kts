dependencies {
    implementation(project(":all"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

sourceSets.main {
    resources.srcDir(rootProject.file("tests/src/test/resources"))
}

tasks.named<ProcessResources>("processResources") {
    include("corpus/dog_exif_extended_xmp_icc.jpg")
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the opt-in Glimt resize and conversion benchmarks."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g")
    args("-rf", "json", "-rff", layout.buildDirectory.file("jmh-results.json").get().asFile.absolutePath)
}

tasks.register<JavaExec>("benchmarkSmoke") {
    group = "verification"
    description = "Executes every JMH case once to catch benchmark and native integration regressions."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g")
    args("-wi", "0", "-i", "1", "-r", "20ms", "-f", "0")
}
