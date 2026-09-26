package com.tailor.engine.blocks;

import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.Slot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PHASE3_SPEC.md step 3.1, {@code tailor blocks}: sections, the projects
 * section, and its positions.
 *
 * <p>Parses {@code word/document.xml} exactly once and reuses that same DOM
 * tree for both Phase 1 bullet detection and section/block detection — the
 * {@link Slot#element()} references from bullet detection have to be the
 * same {@code Element} instances {@link BlockDetector} sees while walking
 * the section's paragraphs, or the bullet-vs-header classification (and the
 * bullet-lock lookup) silently never matches anything.
 */
public final class BlocksAnalyzer {

    private BlocksAnalyzer() {
    }

    public static BlocksReport analyze(Path onboardedDocx) throws IOException {
        DocxPackage pkg = DocxPackage.open(onboardedDocx);
        Document doc = SafeXml.parse(pkg.readPart("word/document.xml"));
        Element numberingRoot = pkg.hasPart("word/numbering.xml")
                ? SafeXml.parse(pkg.readPart("word/numbering.xml")).getDocumentElement() : null;
        Element stylesRoot = pkg.hasPart("word/styles.xml")
                ? SafeXml.parse(pkg.readPart("word/styles.xml")).getDocumentElement() : null;
        NumberingResolver resolver = new NumberingResolver(numberingRoot, stylesRoot);

        List<Slot> allSlots = BulletDetector.detect(doc, resolver);
        Map<Element, Slot> slotByElement = new IdentityHashMap<>();
        for (Slot s : allSlots) {
            slotByElement.put(s.element(), s);
        }

        List<Section> sections = SectionDetector.detect(doc);

        Section projectsSection = null;
        for (Section s : sections) {
            if ("projects".equals(s.role())) {
                projectsSection = s;
                break;
            }
        }

        if (projectsSection == null) {
            return new BlocksReport(sections, null, List.of());
        }

        List<Position> positions = PositionBuilder.build(projectsSection, slotByElement::containsKey, slotByElement);
        return new BlocksReport(sections, projectsSection.heading(), positions);
    }
}
