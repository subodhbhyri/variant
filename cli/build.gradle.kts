plugins {
    id("java")
    id("application")
}

dependencies {
    implementation(project(":engine"))
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.tailor.cli.TailorCli")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.tailor.cli.TailorCli"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
    archiveBaseName.set("tailor")
}
