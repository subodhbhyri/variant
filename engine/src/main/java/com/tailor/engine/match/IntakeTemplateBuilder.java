package com.tailor.engine.match;

import com.tailor.engine.blocks.HeaderParser;
import com.tailor.engine.blocks.Position;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.docx.DomUtil;
import com.tailor.engine.docx.SafeXml;
import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeFields;
import com.tailor.engine.generate.IntakeSection;
import com.tailor.engine.generate.SectionPositions;
import com.tailor.engine.onboard.OnboardReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * P5-T8 operator tooling: {@code tailor intake-template} writes an {@code intake.json}
 * (PHASE4_SPEC.md section 1) pre-filled from the resume itself — one section per job and per
 * swappable project position (D1: jobs are never swapped or added; "no added projects" means only
 * existing positions get a section, never {@code project-new-N}), each section's fields ({@code
 * title}/{@code detail}/{@code date}/{@code links}) read straight off that position's own header,
 * {@code mode: "DETAILED"}, and an empty {@code raw_text} for the operator to fill in.
 */
public final class IntakeTemplateBuilder {

    /** Per {@code "projects"}-role section: its heading and how many positions (total and
     * swappable) {@link #build} found there — printed by {@code tailor intake-template} so a
     * section that structurally looks like projects but yields nothing swappable (e.g. a
     * headerless bullet list) is visible instead of silently contributing zero sections. */
    public record SectionSummary(String heading, int positionCount, int swappableCount) {
    }

    private IntakeTemplateBuilder() {
    }

    public static List<SectionSummary> summarize(Path normalizedDocx, OnboardReport report) throws Exception {
        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        List<SectionSummary> out = new ArrayList<>();
        for (var entry : positions.projectSectionEntries()) {
            long swappable = entry.positions().stream().filter(Position::swappable).count();
            out.add(new SectionSummary(entry.section().heading(), entry.positions().size(), (int) swappable));
        }
        return out;
    }

    public static Intake build(Path normalizedDocx, OnboardReport report) throws Exception {
        SectionPositions positions = SectionPositions.detect(normalizedDocx, report);
        Map<String, String> relationshipTargets = relationshipTargets(normalizedDocx);

        List<IntakeSection> sections = new ArrayList<>();
        List<Position> jobPositions = positions.jobPositions();
        for (int i = 0; i < jobPositions.size(); i++) {
            sections.add(sectionFor("job-" + i, "job", jobPositions.get(i), relationshipTargets));
        }
        List<Position> projectPositions = positions.projectPositions();
        for (int i = 0; i < projectPositions.size(); i++) {
            Position p = projectPositions.get(i);
            if (!p.swappable()) {
                continue;
            }
            sections.add(sectionFor("project-" + i, "project", p, relationshipTargets));
        }
        return new Intake(sections);
    }

    private static IntakeSection sectionFor(String id, String kind, Position p, Map<String, String> relTargets) {
        return new IntakeSection(id, kind, "DETAILED", fieldsFor(p, relTargets), "");
    }

    private static IntakeFields fieldsFor(Position p, Map<String, String> relTargets) {
        if ("inline".equals(p.kind())) {
            // Inline positions fuse header and bullets into one paragraph (no separate
            // title/detail/date/link structure to read); best-effort title only.
            String title = p.block().inlineHeaderSegment;
            return new IntakeFields(title, null, List.of(), null);
        }
        Element header = p.block().headerParas.get(0);
        HeaderParser.Result parsed = HeaderParser.parse(header);
        if (parsed.reason() != null) {
            return new IntakeFields(null, null, List.of(), null);
        }
        HeaderParser.Parsed hp = parsed.parsed();
        return new IntakeFields(hp.title(), hp.detail(), linksOf(header, relTargets), hp.date());
    }

    private static List<IntakeFields.Link> linksOf(Element header, Map<String, String> relTargets) {
        List<IntakeFields.Link> out = new ArrayList<>();
        for (Element el : DomUtil.elementChildren(header)) {
            if ("hyperlink".equals(el.getLocalName())) {
                String rid = DomUtil.attr(el, "id");
                String label = DomUtil.allText(el).strip();
                String url = rid == null ? null : relTargets.get(rid);
                out.add(new IntakeFields.Link(label, url));
            }
        }
        return out;
    }

    private static Map<String, String> relationshipTargets(Path normalizedDocx) throws Exception {
        Map<String, String> out = new HashMap<>();
        DocxPackage pkg = DocxPackage.open(normalizedDocx);
        if (!pkg.hasPart("word/_rels/document.xml.rels")) {
            return out;
        }
        Document rels = SafeXml.parse(pkg.readPart("word/_rels/document.xml.rels"));
        Element root = rels.getDocumentElement();
        if (root == null) {
            return out;
        }
        for (Element rel : DomUtil.elementChildren(root)) {
            if ("Relationship".equals(rel.getLocalName())) {
                String id = DomUtil.attr(rel, "Id");
                String target = DomUtil.attr(rel, "Target");
                if (id != null && target != null) {
                    out.put(id, target);
                }
            }
        }
        return out;
    }
}
