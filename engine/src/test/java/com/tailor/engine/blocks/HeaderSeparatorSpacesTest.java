package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * P5-T15 (PHASE5_SPEC.md section 10, "Separator spaces"): a header's own {@code |} separators
 * may each use a different non-breaking space variant on either side (Word inserts these; a
 * plain {@code " | "} match misses them entirely, which is what made every resume #1 in a real
 * run fail with a 12.65pt shift at one position). {@code Title<NBSP>|<NBSP>Stack Item<space>|
 * <U+2007>} (a hyperlink standing in for "link") must parse to exactly TITLE SEP DETAIL SEP LINK,
 * each SEP token keeping its own exact separator text, and re-emitting the same fields must
 * reproduce those exact characters rather than a generic {@code " | "}.
 */
class HeaderSeparatorSpacesTest {

    private static final String NBSP_SEP = " | ";
    private static final String MIXED_SEP = " | ";

    @Test
    void nonBreakingSeparatorsParseAndReEmitExactly() throws Exception {
        Element p = headerParagraph();

        HeaderParser.Result parsed = HeaderParser.parse(p);
        assertNull(parsed.reason(), "no reason expected");
        HeaderParser.Parsed fields = parsed.parsed();
        assertEquals("Title", fields.title());
        assertEquals("Stack Item", fields.detail());
        assertEquals(1, fields.links().size());
        assertEquals("GitHub", fields.links().get(0).label());

        List<HeaderToken> template = HeaderTemplate.build(p);
        List<HeaderToken.Kind> kinds = template.stream().map(HeaderToken::kind).toList();
        assertEquals(List.of(HeaderToken.Kind.TITLE, HeaderToken.Kind.SEP, HeaderToken.Kind.DETAIL,
                HeaderToken.Kind.SEP, HeaderToken.Kind.LINK), kinds);
        assertEquals(NBSP_SEP, template.get(1).text(), "first separator must keep its own NBSP, not a plain \" | \"");
        assertEquals(MIXED_SEP, template.get(3).text(),
                "second separator must keep its own space+figure-space mix, not a plain \" | \"");

        HeaderRenderer.NewFields newFields = new HeaderRenderer.NewFields(
                "Title", "Stack Item", List.of(new HeaderRenderer.NewLink("GitHub", "https://github.com/example")),
                null);
        HeaderRenderer.render(p, template, newFields, url -> "rId1");

        assertEquals("Title" + NBSP_SEP + "Stack Item" + MIXED_SEP + "GitHub", DomUtil.allText(p),
                "re-emitting the same fields must reproduce the exact original separator characters");
    }

    private static Element headerParagraph() throws Exception {
        String xml = "<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:r><w:t>Title</w:t></w:r>"
                + "<w:r><w:t xml:space=\"preserve\">" + NBSP_SEP + "</w:t></w:r>"
                + "<w:r><w:t>Stack Item</w:t></w:r>"
                + "<w:r><w:t xml:space=\"preserve\">" + MIXED_SEP + "</w:t></w:r>"
                + "<w:hyperlink><w:r><w:t>GitHub</w:t></w:r></w:hyperlink>"
                + "</w:p>";
        Document doc = SafeXml.parse(xml.getBytes(StandardCharsets.UTF_8));
        return doc.getDocumentElement();
    }
}
