import java.time.Duration

plugins {
    id("java")
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Corpus tests shell out to LibreOffice; give them room. Grows with each phase's
        // corpus-level tests (Phase 3 added two more full 9-resume onboard+render passes).
        timeout.set(Duration.ofMinutes(30))
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }

    // The render-heavy tests (@Tag("corpus"): anything that shells out to LibreOffice over the
    // 9-resume corpus or a Phase 2/3 fixture) took the everyday `test` task to ~27 minutes. `test`
    // now excludes them (and @Tag("live"), below) for a fast inner loop; `corpusTest` runs only
    // the corpus ones, with the same JUnit platform, timeout, and logging settings (inherited
    // from the `tasks.withType<Test>` block above, since it matches every Test task including
    // this one).
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("corpus", "live")
        }
    }

    val testSourceSet = the<SourceSetContainer>()["test"]
    tasks.register<Test>("corpusTest") {
        description = "Runs the render-heavy corpus/fixture/rotation tests (@Tag(\"corpus\")) " +
            "that the default test task excludes. Run before every push."
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform {
            includeTags("corpus")
        }
    }

    // @Tag("live"): opt-in tests that call the real Anthropic API (need ANTHROPIC_API_KEY,
    // incur real cost) — e.g. Phase 4's P4-T6/P4-T11. Never run by test, corpusTest, or any
    // "before every push" routine; invoke explicitly with --tests when asked to.
    tasks.register<Test>("liveTest") {
        description = "Runs opt-in tests that call the real Anthropic API (@Tag(\"live\")); " +
            "needs ANTHROPIC_API_KEY, costs real money. Never run automatically."
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform {
            includeTags("live")
        }
    }
}
