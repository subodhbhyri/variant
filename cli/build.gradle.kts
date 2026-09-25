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
    // `configurations.runtimeClasspath.get().filter{}.map{}` below resolves through
    // Kotlin's own Iterable extensions, not Gradle's FileCollection ones, which loses
    // the configuration's Buildable/TaskDependency information. Without this explicit
    // dependsOn, `gradle :cli:jar` run on its own (as the Phase 2 sandbox image build
    // does) never schedules `:engine:jar`, so `engine/build/libs/engine.jar` doesn't
    // exist yet when this task runs and gets silently filtered out by `it.exists()` —
    // producing a fat jar missing every engine class (NoClassDefFoundError at runtime).
    dependsOn(":engine:jar")
    manifest {
        attributes["Main-Class"] = "com.tailor.cli.TailorCli"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
    archiveBaseName.set("tailor")
}
