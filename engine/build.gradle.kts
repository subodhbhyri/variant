plugins {
    id("java-library")
}

dependencies {
    // Package I/O only (OPCPackage) — we do NOT use the XWPF* object model for
    // edits, per PHASE1_SPEC.md section 5. Version pinned; bump deliberately.
    api("org.apache.poi:poi-ooxml:5.3.0")

    // PDF text extraction with per-glyph position (section 6) and page count.
    api("org.apache.pdfbox:pdfbox:3.0.3")

    // JSON for golden files, candidate/edit files, calibration output.
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // PHASE5_SPEC.md section 7: MiniLmEmbedder runs all-MiniLM-L6-v2 in-process, CPU only.
    api("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    // Tokenizer from the model's own tokenizer.json (HuggingFace format) — loaded directly,
    // not re-implemented by hand, so WordPiece tokenization matches the model exactly.
    api("ai.djl.huggingface:tokenizers:0.31.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Declared explicitly (not relied on via `api` propagation) so the golden-file
    // model in src/test compiles regardless of how Gradle wires test/main classpaths.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}
