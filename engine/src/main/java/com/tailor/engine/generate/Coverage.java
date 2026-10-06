package com.tailor.engine.generate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * PHASE4_SPEC.md section 6.2 (coverage): whether a project's kept bullets can fill at least one of the
 * swappable positions in its home section. A position with shape {@code [l1, ..., lr]} is filled by r
 * distinct bullets, the i-th of which has a variant at line count {@code li}.
 */
public final class Coverage {

    /** {@code missingLengths}: the line counts of the uncovered shapes that no kept bullet has a variant at. */
    public record Result(boolean covered, List<Integer> missingLengths) {
    }

    private Coverage() {
    }

    /**
     * @param bullets the kept bullets, each one's variants keyed by line count ("1", "2", ...)
     * @param shapes  the line counts of each home-section position's bullets, one list per position
     */
    public static Result check(List<Map<String, String>> bullets, List<List<Integer>> shapes) {
        // A position with no bullet slots has nothing to fill, so it is not a home position to cover.
        List<List<Integer>> nonEmpty = shapes.stream().filter(s -> !s.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            return new Result(true, List.of()); // nothing to cover
        }
        TreeSet<Integer> missing = new TreeSet<>();
        for (List<Integer> shape : nonEmpty) {
            if (canFill(bullets, shape)) {
                return new Result(true, List.of());
            }
            for (int length : shape) {
                if (bullets.stream().noneMatch(b -> b.containsKey(String.valueOf(length)))) {
                    missing.add(length);
                }
            }
        }
        return new Result(false, new ArrayList<>(missing));
    }

    /** Whether some {@code shape.size()} distinct bullets can take the shape's line counts in order. */
    static boolean canFill(List<Map<String, String>> bullets, List<Integer> shape) {
        return assign(bullets, shape, 0, new boolean[bullets.size()]);
    }

    private static boolean assign(List<Map<String, String>> bullets, List<Integer> shape, int i, boolean[] used) {
        if (i == shape.size()) {
            return true;
        }
        String key = String.valueOf(shape.get(i));
        for (int j = 0; j < bullets.size(); j++) {
            if (!used[j] && bullets.get(j).containsKey(key)) {
                used[j] = true;
                if (assign(bullets, shape, i + 1, used)) {
                    return true;
                }
                used[j] = false;
            }
        }
        return false;
    }
}
