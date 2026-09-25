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
        // Corpus tests shell out to LibreOffice; give them room.
        timeout.set(Duration.ofMinutes(15))
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}
