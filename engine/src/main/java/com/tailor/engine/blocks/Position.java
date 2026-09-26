package com.tailor.engine.blocks;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** One position within the projects section — a block, described for reporting/swapping (PHASE3_SPEC.md section 3). */
public record Position(
        String kind,
        boolean swappable,
        int bullets,
        String reason,
        HeaderInfo header,
        List<Integer> segmentsPerBullet,
        @JsonIgnore Block block) {

    public record HeaderInfo(List<String> fields, boolean detailIsList, int links, String dateMode) {
    }
}
