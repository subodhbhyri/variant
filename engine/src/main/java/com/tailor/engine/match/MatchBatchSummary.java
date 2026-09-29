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

    public static String topSkills(JobDescription jd, int n) {
        return jd.skills().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .map(e -> e.getKey() + "(" + e.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    public static String projectsSummary(AssembledResume resume) {
        return resume.projects().stream()
                .map(p -> p.position() + ":" + p.project())
                .collect(Collectors.joining(", "));
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
