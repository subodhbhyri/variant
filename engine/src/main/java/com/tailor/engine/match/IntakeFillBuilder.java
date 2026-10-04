package com.tailor.engine.match;

import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeFields;
import com.tailor.engine.generate.IntakeSection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P5-T8 operator tooling: {@code tailor intake-fill} matches each project section of an
 * {@code intake-template} output to the operator's own {@code project_datasets.json} by title
 * (case-insensitive, comparing only the part before the first {@code ":"} on both sides), filling
 * in {@code mode: DETAILED}, {@code raw_text}, and the {@code title}/{@code detail}/{@code links}
 * fields from the matched dataset — {@code date} is left as {@code intake-template} found it,
 * since datasets don't carry one. Job sections are set to {@code EXISTING_ONLY} (D2: the operator
 * reviews/polishes existing job bullets through this tool, not regenerates them from scratch).
 * Each dataset is used at most once; unmatched sections and unmatched datasets are both reported,
 * never silently dropped.
 */
public final class IntakeFillBuilder {

    public record Result(Intake intake, List<String> unmatchedSectionIds, List<String> unmatchedDatasetTitles) {
    }

    private IntakeFillBuilder() {
    }

    public static Result fill(Intake intake, ProjectDataset.ProjectDatasets datasets) {
        List<ProjectDataset> all = datasets.datasets();
        Set<Integer> used = new HashSet<>();
        List<IntakeSection> outSections = new ArrayList<>();
        List<String> unmatchedSectionIds = new ArrayList<>();

        for (IntakeSection s : intake.sections()) {
            if ("job".equals(s.kind())) {
                outSections.add(new IntakeSection(s.id(), s.kind(), "EXISTING_ONLY", s.fields(), s.rawText()));
                continue;
            }
            if (!"project".equals(s.kind())) {
                outSections.add(s);
                continue;
            }
            String currentTitle = s.fields() == null ? null : s.fields().title();
            Integer matchIndex = findMatch(currentTitle, all, used);
            if (matchIndex == null) {
                unmatchedSectionIds.add(s.id());
                outSections.add(s);
                continue;
            }
            used.add(matchIndex);
            ProjectDataset d = all.get(matchIndex);
            String date = s.fields() == null ? null : s.fields().date();
            IntakeFields fields = new IntakeFields(beforeColon(d.title()), d.detail(), d.links(), date);
            outSections.add(new IntakeSection(s.id(), s.kind(), "DETAILED", fields, d.rawText()));
        }

        List<String> unmatchedDatasetTitles = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (!used.contains(i)) {
                unmatchedDatasetTitles.add(all.get(i).title());
            }
        }

        return new Result(new Intake(outSections), unmatchedSectionIds, unmatchedDatasetTitles);
    }

    private static Integer findMatch(String sectionTitle, List<ProjectDataset> datasets, Set<Integer> used) {
        if (sectionTitle == null) {
            return null;
        }
        String want = beforeColon(sectionTitle).toLowerCase(Locale.ROOT);
        for (int i = 0; i < datasets.size(); i++) {
            if (used.contains(i)) {
                continue;
            }
            if (beforeColon(datasets.get(i).title()).toLowerCase(Locale.ROOT).equals(want)) {
                return i;
            }
        }
        return null;
    }

    private static String beforeColon(String s) {
        if (s == null) {
            return "";
        }
        int idx = s.indexOf(':');
        return (idx < 0 ? s : s.substring(0, idx)).trim();
    }
}
