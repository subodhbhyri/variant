package com.tailor.engine.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tailor.engine.render.Renderer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Per-stage render counts and wall times, used for match/match-batch's timing.json. */
class PipelineTimingTest {

    @Test
    void eachStageCountsOnlyTheRendersMadeInsideIt() throws Exception {
        CountingRenderer counter = new CountingRenderer(new Renderer() {
            @Override
            public Path render(Path docxPath, Path outDir) {
                return outDir.resolve("out.pdf");
            }

            @Override
            public String version() {
                return "fake";
            }
        });
        PipelineTiming timing = new PipelineTiming(counter);
        Path doc = Path.of("x.docx");

        timing.time("two renders", () -> {
            counter.render(doc, doc);
            counter.render(doc, doc);
            return null;
        });
        timing.time("no renders", () -> null);
        timing.note("swap P1=harbor FAILED: BOUNDS");

        assertEquals(List.of("two renders", "no renders", "swap P1=harbor FAILED: BOUNDS"),
                timing.stages().stream().map(PipelineTiming.Stage::name).toList());
        assertEquals(2, timing.stages().get(0).renders());
        assertEquals(0, timing.stages().get(1).renders());
        assertEquals(2, timing.totalRenders());
    }
}
