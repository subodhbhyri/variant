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
}
