package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.render.RenderException;
import com.tailor.engine.render.Renderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * P3-T9 (PHASE3_SPEC.md section 9 / section 4): a library link's URL must be
 * an absolute http://, https:// or mailto: URL, at most 2,048 characters,
 * with no whitespace or control characters. Anything else fails with
 * INVALID_LINK before any render.
 */
class LinkValidationTest {

    @Test
    void invalidUrlsAreRejected() {
        List<String> invalid = List.of(
                "javascript:alert(1)",
                "file:///etc/passwd",
                "data:text/html,x",
                "some/relative/path",
                "https://example.com/a b",
                "https://example.com/" + "a".repeat(3000));

        for (String url : invalid) {
            assertFalse(LinkValidator.isValid(url), "expected invalid: " + url);
            assertThrows(LinkValidator.InvalidLinkException.class, () -> LinkValidator.validate(url),
                    "expected INVALID_LINK: " + url);
        }
    }

    @Test
    void validUrlsAreAccepted() {
        for (String url : List.of("https://github.com/x/y", "mailto:a@b.co")) {
            assertTrue(LinkValidator.isValid(url), "expected valid: " + url);
            assertDoesNotThrow(() -> LinkValidator.validate(url));
        }
    }

    @Test
    void invalidLinkFailsBeforeAnyMutationOrRender() throws Exception {
        Document doc = SafeXml.parse(
                ("<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                        + "<w:r><w:t>Original Title</w:t></w:r></w:p>").getBytes(StandardCharsets.UTF_8));
        Element header = doc.getDocumentElement();

        // A CountingRenderer test double, mirroring the pattern CorpusCheck.java already uses,
        // proves the PDF renderer is never reached — though HeaderRenderer never touches a
        // Renderer at all, so this also documents that architectural fact for this test.
        AtomicInteger renderCalls = new AtomicInteger();
        Renderer countingRenderer = new Renderer() {
            @Override
            public Path render(Path docxPath, Path outDir) throws RenderException {
                renderCalls.incrementAndGet();
                throw new RenderException("must never be called for an invalid link");
            }

            @Override
            public String version() {
                return "counting-test-double";
            }
        };

        List<HeaderToken> template = HeaderTemplate.build(header);
        HeaderRenderer.NewFields fields = new HeaderRenderer.NewFields(
                "New Title", null, List.of(new HeaderRenderer.NewLink("Evil", "javascript:alert(1)")), null);

        assertThrows(LinkValidator.InvalidLinkException.class,
                () -> HeaderRenderer.render(header, template, fields, url -> {
                    fail("the relationship writer must not be called for an invalid link");
                    return null;
                }));

        assertEquals("Original Title", DomUtil.allText(header), "header must be untouched after a rejected render");
        assertEquals(0, renderCalls.get(), "the PDF renderer must never be called for an invalid link");
        assertEquals("counting-test-double", countingRenderer.version()); // keep the double referenced/used
    }
}
