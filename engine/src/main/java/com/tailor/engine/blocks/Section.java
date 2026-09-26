package com.tailor.engine.blocks;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import org.w3c.dom.Element;

/**
 * One heading-delimited section of the body (PHASE3_SPEC.md section 2).
 *
 * @param heading    the heading paragraph's own text, trimmed
 * @param role       "projects", "experience", or "other" (suggested; user-confirmable at onboarding)
 * @param paragraphs every body paragraph between this heading and the next (or end of document)
 */
public record Section(String heading, String role, @JsonIgnore List<Element> paragraphs) {
}
