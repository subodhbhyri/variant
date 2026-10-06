package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.CorpusPaths;
import com.tailor.engine.blocks.BatchAssembler;
import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.docx.DocxPackage;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.onboard.OnboardPipeline;
import com.tailor.engine.onboard.OnboardReport;
import com.tailor.engine.render.LibreOfficeRenderer;
import com.tailor.engine.render.Renderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The batched path must not refuse a project that stays at its own original position. Reproduces the private
 * run's refusal ("stack fit probes are not uniquely anchored", after the stack-fit rounds) on the synthetic
 * projects resume. Position P1's original header is "Ledger Sync | Go, Postgres, gRPC | GitHub Aug 2023 – Dec 2023"
 * (Ledger Sync is not in the library); a library entry with exactly that detail, date and link keeps P1, so its
 * full-detail probe is the header the resume already has there. The batched output must then equal the per-position
 * output, byte for byte in word/document.xml. Corpus tag: renders through LibreOffice.
 */
@Tag("corpus")
class BatchedAmbiguityTest {

    @Test
    void aProjectAtItsOwnOriginalPositionIsNotRefused() throws Exception {
        Renderer renderer = new LibreOfficeRenderer();
        FontMap fontMap = FontMap.loadDefault();
        Path onboardDir = Files.createTempDirectory("ambiguity-onboard");
        byte[] upload = Files.readAllBytes(CorpusPaths.phase3FixturesDir().resolve("projects_synthetic.docx"));
        OnboardReport report = new OnboardPipeline(renderer, fontMap).run(upload, onboardDir);
        Assumptions.assumeTrue(report.accepted(), "fixture failed to onboard: " + report.reason());

        Path fixtures = CorpusPaths.phase5FixturesDir().resolve("match_run");
        MatchRunner.Context ctx = MatchRunner.buildContext(onboardDir.resolve("normalized.docx"),
                fixtures.resolve("variants.json"), CorpusPaths.phase3FixturesDir().resolve("library.json"),
                SkillsDictionary.loadDefault(), new FakeEmbedder(), renderer, fontMap);

        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonNode resume = mapper.readTree(fixtures.resolve("golden").resolve("platform").resolve("match.json").toFile())
                .get("resumes").get(0);
        List<String> job = new ArrayList<>();
        for (JsonNode id : resume.get("job")) {
            job.add(id.isNull() ? null : id.asText());
        }
        Map<String, LibraryProject> library = new LinkedHashMap<>();
        ctx.library().forEach(p -> library.put(p.id(), p));
        LibraryProject harbor = library.get("harbor");

        // Ledger Sync: its original header's detail, date and link, with bullets from Harbor for P1's [2,1] shape.
        LibraryProject ledger = new LibraryProject("ledger", "Ledger Sync", "Go, Postgres, gRPC",
                List.of(new LibraryProject.Link("GitHub", "https://github.com/example/ledger")), "Aug 2023 – Dec 2023",
                harbor.bullets(), null);
        library.put(ledger.id(), ledger);

        Map<String, Integer> golden = new LinkedHashMap<>();
        Map<String, JsonNode> goldenByPosition = new LinkedHashMap<>();
        for (JsonNode p : resume.get("projects")) {
            goldenByPosition.put(p.get("position").asText(), p);
            golden.put(p.get("position").asText(), 0);
        }
        Map<String, List<Integer>> shapeByPosition = new LinkedHashMap<>();
        ctx.shapes().positions().forEach(p -> shapeByPosition.put(p.id(), p.shape()));

        List<AssembledResume.ProjectAssignment> assignments = new ArrayList<>();
        assignments.add(golden("P0", goldenByPosition.get("P0")));
        assignments.add(picked("P1", ledger, shapeByPosition.get("P1")));
        assignments.add(golden("P2", goldenByPosition.get("P2")));
        assignments.add(golden("P3", goldenByPosition.get("P3")));
        assertEquals(4, assignments.size());

        List<BatchAssembler.Plan> plans = new ArrayList<>();
        for (AssembledResume.ProjectAssignment a : assignments) {
            LibraryProject p = library.get(a.project());
            List<Map<String, String>> selected = new ArrayList<>();
            for (int idx : a.bullets()) {
                selected.add(p.bullets().get(idx));
            }
            plans.add(new BatchAssembler.Plan(Integer.parseInt(a.position().substring(1)),
                    new LibraryProject(p.id(), p.title(), p.detail(), p.links(), p.date(), selected, p.homeSection())));
        }

        Path workDir = Files.createTempDirectory("ambiguity-work");
        Path batchedOut = Files.createTempDirectory("ambiguity-batched").resolve("resume-1.docx");
        BatchAssembler.Result batched = BatchAssembler.assemble(onboardDir.resolve("normalized.docx"), ctx.baseline(),
                ctx.baselinePdf(), ctx.report(), job, ctx.jobCandidatesById(), plans, ctx.renderer(),
                MatchRunner.productionCheck(ctx), workDir, batchedOut, null);
        assertTrue(batched.ok(), "the batched path must place this resume: " + batched.detail());

        Path perPositionOut = Files.createTempDirectory("ambiguity-per-position").resolve("resume-1.docx");
        ResumeRenderer.RenderResult perPosition = ResumeRenderer.render(onboardDir.resolve("normalized.docx"),
                ctx.report(), new AssembledResume(job, assignments, null, null), ctx.jobCandidatesById(),
                new ArrayList<>(library.values()), ctx.renderer(), ctx.fontMap(), workDir, perPositionOut);
        assertTrue(perPosition.ok(), "the per-position path places it too: " + perPosition.detail());

        assertArrayEquals(DocxPackage.open(perPositionOut).readPart("word/document.xml"),
                DocxPackage.open(batchedOut).readPart("word/document.xml"),
                "the batched output must equal the per-position output");
    }

    private static AssembledResume.ProjectAssignment golden(String position, JsonNode entry) {
        List<Integer> bullets = new ArrayList<>();
        for (JsonNode b : entry.get("bullets")) {
            bullets.add(b.asInt());
        }
        return new AssembledResume.ProjectAssignment(position, entry.get("project").asText(), bullets,
                entry.get("score").asDouble(), null);
    }

    /** A project placed at a position of the given shape: for each slot, the first unused bullet with that line count. */
    private static AssembledResume.ProjectAssignment picked(String position, LibraryProject project, List<Integer> shape) {
        List<Integer> bullets = new ArrayList<>();
        for (int length : shape) {
            for (int j = 0; j < project.bullets().size(); j++) {
                if (!bullets.contains(j) && project.bullets().get(j).containsKey(String.valueOf(length))) {
                    bullets.add(j);
                    break;
                }
            }
        }
        assertEquals(shape.size(), bullets.size(), project.id() + " can't fill " + position + " " + shape);
        return new AssembledResume.ProjectAssignment(position, project.id(), bullets, 0.0, null);
    }
}
