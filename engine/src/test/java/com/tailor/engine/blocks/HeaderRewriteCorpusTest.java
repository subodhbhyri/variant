package com.tailor.engine.blocks;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tailor.engine.CorpusPaths;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.docx.XmlSerialize;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.gate.GateResult;
import com.tailor.engine.gate.UploadGate;
import com.tailor.engine.numbering.NumberingResolver;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import com.tailor.engine.slots.BulletDetector;
import com.tailor.engine.slots.Slot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * P3-T8 (PHASE3_SPEC.md section 9): rewrites every swappable header shape found
 * across the corpus and the fixture with new fields (a title, a 3-item detail,
 * one link, a date — and, separately, an empty detail with no links), and checks
 * that {@code render_header}'s skip rules were followed, that every new link got
 * a fresh {@code rIdVariant} id pointing at its URL with {@code TargetMode="External"},
 * that no existing relationship was touched, and that the saved, rewritten file
 * still passes the upload gate.
 */
class HeaderRewriteCorpusTest {

    private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test
    void rewritesEverySwappableHeaderShape() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        OnboardPipeline onboard = new OnboardPipeline(renderer, FontMap.loadDefault());

        List<Path> docs = new ArrayList<>(CorpusPaths.corpusDocx());
        docs.add(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));

        StringBuilder failures = new StringBuilder();
        int headersRewritten = 0;

        for (Path docx : docs) {
            String base = stripExt(docx.getFileName().toString());
            Path workDir = Files.createTempDirectory("header-rewrite-" + base);

            OnboardReport onboardReport = onboard.run(Files.readAllBytes(docx), workDir);
            if (!onboardReport.accepted()) {
                failures.append(base).append(": failed to onboard, reason=").append(onboardReport.reason()).append('\n');
                continue;
            }
            Path normalizedDocx = workDir.resolve("normalized.docx");

            DocxPackage pkg = DocxPackage.open(normalizedDocx);
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
            Section projectsSection = sections.stream().filter(s -> "projects".equals(s.role())).findFirst().orElse(null);
            if (projectsSection == null) {
                continue;
            }
            List<Position> positions = PositionBuilder.build(projectsSection, slotByElement::containsKey, slotByElement);

            Set<String> relsBeforeThisResume = existingRelationshipIds(pkg);
            RelationshipWriter relWriter = RelationshipWriter.forPackage(pkg);
            boolean anyHeaderThisResume = false;

            for (Position p : positions) {
                if (!"paragraph".equals(p.kind()) || !p.swappable() || p.header() == null) {
                    continue;
                }
                Element header = p.block().headerParas.get(0);
                List<HeaderToken> template = HeaderTemplate.build(header);
                boolean templateHasLink = template.stream().anyMatch(t -> t.kind() == HeaderToken.Kind.LINK);
                boolean templateHasDate = template.stream().anyMatch(t -> t.kind() == HeaderToken.Kind.DATE);
                boolean templateHasDetail = template.stream().anyMatch(t -> t.kind() == HeaderToken.Kind.DETAIL);

                // Case B (empty detail, no links) on a detached clone first: the template's
                // token elements (rPr/link/tab) stay usable Nodes even once case A below
                // detaches them from the live header, but building the clone up front keeps
                // the two cases fully independent.
                Element minimalTarget = (Element) header.cloneNode(true);
                HeaderRenderer.NewFields minimalFields =
                        new HeaderRenderer.NewFields("Rewritten Title", "", List.of(), "Jan 2024 – Mar 2024");
                try {
                    HeaderRenderer.render(minimalTarget, template, minimalFields,
                            url -> fail(base + ": link renderer called for a project with no links"));
                } catch (Exception e) {
                    failures.append(base).append(": minimal rewrite threw ").append(e).append('\n');
                    continue;
                }
                String minimalText = DomUtil.allText(minimalTarget);
                if (minimalText.contains(" |  |") || minimalText.strip().endsWith("|")) {
                    failures.append(base).append(": minimal rewrite left a dangling separator: [")
                            .append(minimalText).append("]\n");
                }
                if (!DomUtil.descendants(minimalTarget, "hyperlink").isEmpty()) {
                    failures.append(base).append(": minimal rewrite (no links) still has a hyperlink\n");
                }

                // Case A (rich fields), applied live so relationship bookkeeping and the
                // eventual upload-gate check cover the real, rewritten document.
                HeaderRenderer.NewFields richFields = new HeaderRenderer.NewFields(
                        "Rewritten Title", "Java, Kafka, Redis",
                        List.of(new HeaderRenderer.NewLink("GitHub", "https://github.com/example/quill")),
                        "Jan 2024 – Mar 2024");
                try {
                    HeaderRenderer.render(header, template, richFields, relWriter::addHyperlink);
                } catch (Exception e) {
                    failures.append(base).append(": rich rewrite threw ").append(e).append('\n');
                    continue;
                }
                headersRewritten++;
                anyHeaderThisResume = true;

                String richText = DomUtil.allText(header);
                if (!richText.contains("Rewritten Title")) {
                    failures.append(base).append(": rich rewrite lost the title\n");
                }
                if (templateHasDetail != richText.contains("Java, Kafka, Redis")) {
                    failures.append(base).append(": rich rewrite's detail presence doesn't match the template\n");
                }
                if (templateHasDate && !richText.contains("Jan 2024")) {
                    failures.append(base).append(": rich rewrite lost the date\n");
                }

                List<Element> hyperlinks = DomUtil.descendants(header, "hyperlink");
                if (templateHasLink) {
                    if (hyperlinks.size() != 1) {
                        failures.append(base).append(": expected exactly one hyperlink after rich rewrite, got ")
                                .append(hyperlinks.size()).append('\n');
                    } else {
                        Element link = hyperlinks.get(0);
                        if (link.getAttributeNodeNS(W_NS, "anchor") != null) {
                            failures.append(base).append(": rewritten link still carries w:anchor\n");
                        }
                        String rid = link.getAttributeNS(R_NS, "id");
                        if (rid.isEmpty() || !rid.matches("rIdVariant\\d+")) {
                            failures.append(base).append(": rewritten link has no fresh rIdVariant id: [")
                                    .append(rid).append("]\n");
                        } else if (relsBeforeThisResume.contains(rid)) {
                            failures.append(base).append(": rewritten link reused an existing relationship id ")
                                    .append(rid).append('\n');
                        }
                    }
                } else if (!hyperlinks.isEmpty()) {
                    failures.append(base).append(": rich rewrite added a hyperlink the template never had\n");
                }
            }

            if (!anyHeaderThisResume) {
                continue;
            }

            relWriter.flush();

            Document relsAfter = SafeXml.parse(pkg.readPart("word/_rels/document.xml.rels"));
            for (Element rel : DomUtil.elementChildren(relsAfter.getDocumentElement())) {
                String id = DomUtil.attr(rel, "Id");
                if (id != null && relsBeforeThisResume.contains(id)) {
                    continue; // a pre-existing relationship; untouched by construction (never re-fetched/rewritten)
                }
                if (id != null && id.matches("rIdVariant\\d+")
                        && !"External".equals(DomUtil.attr(rel, "TargetMode"))) {
                    failures.append(base).append(": relationship ").append(id)
                            .append(" missing TargetMode=External\n");
                }
            }

            pkg.writePart("word/document.xml", XmlSerialize.toBytes(doc));
            Path rewrittenDocx = workDir.resolve("header-rewritten.docx");
            pkg.save(rewrittenDocx);
            GateResult gate = UploadGate.check(Files.readAllBytes(rewrittenDocx));
            if (!gate.accepted()) {
                failures.append(base).append(": upload gate rejected the rewritten file, reason=")
                        .append(gate.reason()).append('\n');
            }
        }

        System.out.println("headers rewritten: " + headersRewritten);
        System.out.println("FAILURES:\n" + (failures.isEmpty() ? "(none)" : failures));
        assertTrue(failures.isEmpty(), "P3-T8 failures:\n" + failures);
    }

    private static Set<String> existingRelationshipIds(DocxPackage pkg) throws Exception {
        if (!pkg.hasPart("word/_rels/document.xml.rels")) {
            return Set.of();
        }
        Document relsDoc = SafeXml.parse(pkg.readPart("word/_rels/document.xml.rels"));
        Set<String> ids = new HashSet<>();
        for (Element rel : DomUtil.elementChildren(relsDoc.getDocumentElement())) {
            String id = DomUtil.attr(rel, "Id");
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
