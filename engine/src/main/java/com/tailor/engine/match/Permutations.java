package com.tailor.engine.match;

import java.util.ArrayList;
import java.util.List;

/**
 * k-permutations of {@code range(n)}, generated in exactly the order Python's
 * {@code itertools.permutations(range(n), k)} produces them: at each position, try the smallest
 * unused index first. {@code reference/jd_ref.py}'s tie-breaking ("first candidate found wins")
 * in {@code place_project} and {@code assign_projects} depends on this exact order.
 */
final class Permutations {

    private Permutations() {
    }

    static List<int[]> of(int n, int k) {
        List<int[]> out = new ArrayList<>();
        boolean[] used = new boolean[n];
        int[] current = new int[k];
        generate(n, k, 0, current, used, out);
        return out;
    }

    private static void generate(int n, int k, int depth, int[] current, boolean[] used, List<int[]> out) {
        if (depth == k) {
            out.add(current.clone());
            return;
        }
        for (int i = 0; i < n; i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            current[depth] = i;
            generate(n, k, depth + 1, current, used, out);
            used[i] = false;
        }
    }
}
