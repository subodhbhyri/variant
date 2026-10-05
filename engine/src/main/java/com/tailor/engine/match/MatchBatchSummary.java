package com.tailor.engine.match;

import com.tailor.engine.blocks.LibraryProject;
import com.tailor.engine.generate.SkillsDictionary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * P5-T8 operator tooling: the per-job-description row and Markdown table {@code tailor
 * match-batch} writes to {@code summary.md} — extracted so it's directly testable against
 * fixtures, independent of the CLI's file I/O.
 */
public final class MatchBatchSummary {

    private MatchBatchSummary() {
    }

    public record Row(String jdName, String title, String topSkills, String projects, String alternatives,
            String missingSkills, String cache, String time) {
    }

    /** Compares {@code jd} against every earlier posting in the batch, in order; the first HIT
     * wins (matching what a real fingerprint cache would return: any prior match reuses). */
    public static String cacheAgainstPrevious(JobDescription jd, List<JobDescription> previousJds,
            List<String> previousNames, Embedder embedder) {
        for (int i = 0; i < previousJds.size(); i++) {
            CacheDecision d = JdCache.decide(jd, previousJds.get(i), embedder);
            if ("HIT".equals(d.decision())) {
                return "HIT vs " + previousNames.get(i) + " (jaccard=" + d.weightedJaccard() + ")";
            }
        }
        return "MISS";
    }

    public static Row rowFor(String jdName, MatchRunner.Result result, List<JobDescription> previousJds,
            List<String> previousNames, Embedder embedder) {
        return new Row(jdName, result.jd().title(), topSkills(result.jd(), 5),
                projectsSummary(result.resumes().get(0)), alternativesSummary(result.resumes()),
                result.missing().isEmpty() ? "(none)" : String.join(", ", result.missing()),
                cacheAgainstPrevious(result.jd(), previousJds, previousNames, embedder), formatTime(result.timing().totalMs()));
    }

    /** PHASE5_SPEC.md section 5: resume #1 dropped outright (no feasible project assignment, or
     * the final whole-document verify failed after fail-soft gave up) — still one row, never a
     * silently-skipped posting. */
    public static Row failedRow(String jdName, JobDescription jd, String reason, long totalMs) {
        return new Row(jdName, jd.title(), topSkills(jd, 5), "FAILED: " + reason, "(none)", "(none)", "(none)",
                formatTime(totalMs));
    }

    public static String topSkills(JobDescription jd, int n) {
        return jd.skills().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .map(e -> e.getKey() + "(" + e.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    /** PHASE5_SPEC.md section 5: a position fail-soft gave up on (left unswapped, at its own
     * original content) is appended as a note, so a degraded posting's row still carries its
     * reason, same as the fully-dropped ({@link #failedRow}) case. */
    public static String projectsSummary(AssembledResume resume) {
        String base = resume.projects().stream()
                .map(p -> p.position() + ":" + p.project())
                .collect(Collectors.joining(", "));
        if (resume.degraded().isEmpty()) {
            return base;
        }
        String note = resume.degraded().stream()
                .map(d -> d.position() + " degraded: " + d.reason())
                .collect(Collectors.joining("; "));
        return base.isEmpty() ? note : base + " (" + note + ")";
    }

    public static String alternativesSummary(List<AssembledResume> resumes) {
        if (resumes.size() <= 1) {
            return "(none)";
        }
        return resumes.subList(1, resumes.size()).stream()
                .map(AssembledResume::label)
                .collect(Collectors.joining("; "));
    }

    public static String toMarkdown(List<Row> rows) {
        StringBuilder md = new StringBuilder();
        md.append("# Match batch summary\n\n");
        md.append("| JD | Title | Top 5 skills | Resume #1 projects | Alternatives | Missing skills | Cache | Time |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append("| ").append(r.jdName())
                    .append(" | ").append(escape(r.title()))
                    .append(" | ").append(r.topSkills())
                    .append(" | ").append(r.projects())
                    .append(" | ").append(r.alternatives())
                    .append(" | ").append(r.missingSkills())
                    .append(" | ").append(r.cache())
                    .append(" | ").append(r.time()).append(" |\n");
        }
        return md.toString();
    }

    /** Wall time for the posting's whole run, shown to one decimal place of a second. */
    public static String formatTime(long totalMs) {
        return String.format(java.util.Locale.ROOT, "%.1f s", totalMs / 1000.0);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }

    /** PHASE5_SPEC.md section 9 (revision 4): for each library project, the swappable positions
     * whose shape it can fill — independent of any job description (a project lacking 2-line
     * variants can't fill an all-2-line position, whatever the posting asks for), so it's the
     * same for every posting in a batch and computed once. {@code jd}/{@code skills}/{@code
     * embedder} are only needed because {@link Assembler#placeProject} always scores as it
     * checks fit; an empty job description scores everything 0 without affecting which
     * permutations exist at all. */
    public static Map<String, List<String>> feasibility(List<LibraryProject> library,
            List<Shapes.PositionShape> positions, SkillsDictionary skills, Embedder embedder) {
        JobDescription empty = new JobDescription("", Map.of(), "");
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (LibraryProject p : library) {
            List<String> fits = new ArrayList<>();
            for (Shapes.PositionShape pos : positions) {
                if (Assembler.placeProject(p, pos.shape(), empty, skills, embedder) != null) {
                    fits.add(pos.id());
                }
            }
            out.put(p.id(), fits);
        }
        return out;
    }

    public static String feasibilityMarkdown(Map<String, List<String>> feasibility) {
        StringBuilder md = new StringBuilder();
        md.append("\n## Library project feasibility\n\n");
        md.append("| Project | Positions it can fill |\n|---|---|\n");
        for (Map.Entry<String, List<String>> e : feasibility.entrySet()) {
            md.append("| ").append(e.getKey()).append(" | ")
                    .append(e.getValue().isEmpty() ? "(none)" : String.join(", ", e.getValue())).append(" |\n");
        }
        return md.toString();
    }
}
