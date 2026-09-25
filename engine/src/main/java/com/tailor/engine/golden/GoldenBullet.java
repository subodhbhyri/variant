package com.tailor.engine.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One bullet's expected answer, from golden/*.json (produced by reference/make_golden.py). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenBullet(
        int index,
        List<Integer> locator,
        String text,
        int chars,
        int lines,
        List<GoldenSpan> emphasis,
        Integer maxCharsProse,
        Integer maxCharsTech,
        boolean supported,
        List<String> unsupportedReasons) {
}
