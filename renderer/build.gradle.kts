plugins {
    id("java")
    id("application")
}

dependencies {
    // PHASE6_SPEC.md section 2.1: a pool of persistent LibreOffice processes. JODConverter owns the
    // pool mechanics (restart after N renders, on a crash and on a timeout); this module adds the
    // HTTP face, the per-request scratch directory and the limits.
    implementation("org.jodconverter:jodconverter-local:4.4.9")
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")

    // Tests only: the renderer itself does not know the engine. The identity test (P6-T9) compares
    // RemoteRenderer with the local LibreOfficeRenderer on the corpus.
    testImplementation(project(":engine"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.tailor.renderer.RendererMain")
}
