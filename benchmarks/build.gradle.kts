dependencies {
    implementation(project(":all"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
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
