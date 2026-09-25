package com.tailor.engine.docx;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * PHASE2_SPEC.md check 12: SafeXml must reject a DOCTYPE with an entity
 * before any depth or content processing happens. {@link SafeXml#parse}
 * runs its {@code checkDepth} SAX pre-pass first, so this exercises that
 * pre-pass's hardening (checkDepth itself is private).
 */
class SafeXmlTest {

    @Test
    void rejectsDoctypeWithEntity() {
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE root [ <!ENTITY xxe \"pwned\"> ]>\n"
                + "<root>&xxe;</root>";

        assertThrows(IOException.class,
                () -> SafeXml.parse(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
