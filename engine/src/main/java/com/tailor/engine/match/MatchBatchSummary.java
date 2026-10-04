package com.tailor.engine.match;

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
            String missingSkills, String cache) {
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
                cacheAgainstPrevious(result.jd(), previousJds, previousNames, embedder));
    }

    /** PHASE5_SPEC.md section 5: resume #1 dropped outright (no feasible project assignment, or
     * the final whole-document verify failed after fail-soft gave up) — still one row, never a
     * silently-skipped posting. */
    public static Row failedRow(String jdName, JobDescription jd, String reason) {
        return new Row(jdName, jd.title(), topSkills(jd, 5), "FAILED: " + reason, "(none)", "(none)", "(none)");
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
        md.append("| JD | Title | Top 5 skills | Resume #1 projects | Alternatives | Missing skills | Cache |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append("| ").append(r.jdName())
                    .append(" | ").append(escape(r.title()))
                    .append(" | ").append(r.topSkills())
                    .append(" | ").append(r.projects())
                    .append(" | ").append(r.alternatives())
                    .append(" | ").append(r.missingSkills())
                    .append(" | ").append(r.cache()).append(" |\n");
        }
        return md.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }
}
