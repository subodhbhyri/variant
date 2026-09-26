package com.tailor.engine.blocks;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;

/**
 * One project/job block within a section (PHASE3_SPEC.md section 3, reference: {@code detect_blocks}).
 * Paragraph-kind blocks use {@link #headerParas}/{@link #bulletParas}/{@link #strayParas};
 * inline-kind blocks use {@link #inlineHeaderParagraph}/{@link #inlineHeaderSegment}/{@link #inlineBulletGroups}.
 */
public final class Block {

    public enum Kind { PARAGRAPH, INLINE }

    public final Kind kind;
    public final List<Element> headerParas = new ArrayList<>();
    public final List<Element> bulletParas = new ArrayList<>();
    public final List<Element> strayParas = new ArrayList<>();
    public String reason;

    // Inline-kind fields.
    public Element inlineHeaderParagraph;
    public String inlineHeaderSegment;
    /** Each inner list is one inline bullet's segments (its own text plus any continuation segments). */
    public List<List<String>> inlineBulletGroups = new ArrayList<>();

    public Block(Kind kind) {
        this.kind = kind;
    }
}
