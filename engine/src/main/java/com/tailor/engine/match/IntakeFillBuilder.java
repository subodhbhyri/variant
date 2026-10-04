package com.tailor.engine.match;

import com.tailor.engine.generate.Intake;
import com.tailor.engine.generate.IntakeFields;
import com.tailor.engine.generate.IntakeSection;
import com.tailor.engine.generate.IntakeValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P5-T8 operator tooling: {@code tailor intake-fill} matches each project section of an
 * {@code intake-template} output to the operator's own {@code project_datasets.json} ({@link
 * ProjectDataset.ProjectDatasets}) by title (case-insensitive, comparing only the part before the
 * first {@code ":"} on both sides — the dataset map's own key is only a display name, never
 * compared), filling in {@code mode: DETAILED}, {@code raw_text}, and the {@code
 * title}/{@code detail}/{@code links} fields from the matched dataset — {@code date} is left as
 * {@code intake-template} found it, since datasets don't carry one. Job sections are set to
 * {@code EXISTING_ONLY} (D2: the operator reviews/polishes existing job bullets through this
 * tool, not regenerates them from scratch). Each dataset is used at most once; unmatched sections
 * and unmatched datasets are both reported, never silently dropped — unless {@code addUnmatched}
 * turns a dataset matching no existing section into a brand-new added project ({@code
 * project-new-N}, {@code DETAILED}, up to {@link IntakeValidator#MAX_ADDED_PROJECTS} total; its
 * home section follows the same rule {@code tailor generate} already uses for any other added
 * project — the section whose heading contains "project", else the one with the most positions).
 */
public final class IntakeFillBuilder {

    /** The exact shape {@code project_datasets.json} must have — shown to the operator whenever
     * the file doesn't parse into it, instead of a raw exception. */
    public static final String EXPECTED_SHAPE =
            "{\"projects\": {\"<display name>\": {\"fields\": {\"title\": \"...\", \"detail\": \"...\", "
                    + "\"links\": [{\"label\": \"...\", \"url\": \"...\"}]}, \"raw_text\": \"...\"}}}";

    private static final Pattern ADDED_PROJECT_ID = Pattern.compile("^project-new-(\\d+)$");

    public record Result(Intake intake, List<String> unmatchedSectionIds, List<String> unmatchedDatasetNames) {
    }

    private IntakeFillBuilder() {
    }

    public static Result fill(Intake intake, ProjectDataset.ProjectDatasets datasets) {
        return fill(intake, datasets, false);
    }

    public static Result fill(Intake intake, ProjectDataset.ProjectDatasets datasets, boolean addUnmatched) {
        if (datasets == null || datasets.projects() == null) {
            throw new IllegalArgumentException(
                    "project_datasets.json must have the shape " + EXPECTED_SHAPE);
        }
        List<Map.Entry<String, ProjectDataset>> entries = new ArrayList<>(datasets.projects().entrySet());
        for (Map.Entry<String, ProjectDataset> e : entries) {
            if (e.getValue() == null || e.getValue().fields() == null || e.getValue().fields().title() == null) {
                throw new IllegalArgumentException("project_datasets.json entry \"" + e.getKey()
                        + "\" is missing fields.title; expected shape " + EXPECTED_SHAPE);
            }
        }

        Set<Integer> used = new HashSet<>();
        List<IntakeSection> outSections = new ArrayList<>();
        List<String> unmatchedSectionIds = new ArrayList<>();
        int existingAddedProjects = 0;

        for (IntakeSection s : intake.sections()) {
            if ("job".equals(s.kind())) {
                outSections.add(new IntakeSection(s.id(), s.kind(), "EXISTING_ONLY", s.fields(), s.rawText()));
                continue;
            }
            if (!"project".equals(s.kind())) {
                outSections.add(s);
                continue;
            }
            if (ADDED_PROJECT_ID.matcher(s.id()).matches()) {
                existingAddedProjects++;
            }
            String currentTitle = s.fields() == null ? null : s.fields().title();
            Integer matchIndex = findMatch(currentTitle, entries, used);
            if (matchIndex == null) {
                unmatchedSectionIds.add(s.id());
                outSections.add(s);
                continue;
            }
            used.add(matchIndex);
            ProjectDataset d = entries.get(matchIndex).getValue();
            String date = s.fields() == null ? null : s.fields().date();
            IntakeFields fields = new IntakeFields(
                    beforeColon(d.fields().title()), d.fields().detail(), d.fields().links(), date);
            outSections.add(new IntakeSection(s.id(), s.kind(), "DETAILED", fields, d.rawText()));
        }

        List<String> unmatchedDatasetNames = new ArrayList<>();
        List<Integer> unmatchedIndices = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!used.contains(i)) {
                unmatchedDatasetNames.add(entries.get(i).getKey());
                unmatchedIndices.add(i);
            }
        }

        if (addUnmatched) {
            int addedSoFar = existingAddedProjects;
            List<String> turnedIntoProjects = new ArrayList<>();
            for (int i : unmatchedIndices) {
                if (addedSoFar >= IntakeValidator.MAX_ADDED_PROJECTS) {
                    break; // cap reached; the rest stay reported as unmatched, never silently dropped
                }
                addedSoFar++;
                Map.Entry<String, ProjectDataset> entry = entries.get(i);
                ProjectDataset d = entry.getValue();
                IntakeFields fields =
                        new IntakeFields(beforeColon(d.fields().title()), d.fields().detail(), d.fields().links(), null);
                outSections.add(
                        new IntakeSection("project-new-" + addedSoFar, "project", "DETAILED", fields, d.rawText()));
                turnedIntoProjects.add(entry.getKey());
            }
            unmatchedDatasetNames.removeAll(turnedIntoProjects);
        }

        return new Result(new Intake(outSections), unmatchedSectionIds, unmatchedDatasetNames);
    }

    private static Integer findMatch(String sectionTitle, List<Map.Entry<String, ProjectDataset>> entries,
            Set<Integer> used) {
        if (sectionTitle == null) {
            return null;
        }
        String want = beforeColon(sectionTitle).toLowerCase(Locale.ROOT);
        for (int i = 0; i < entries.size(); i++) {
            if (used.contains(i)) {
                continue;
            }
            String have = beforeColon(entries.get(i).getValue().fields().title()).toLowerCase(Locale.ROOT);
            if (have.equals(want)) {
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
