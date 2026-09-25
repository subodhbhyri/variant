package com.tailor.engine.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tailor.engine.layout.Locker.LockedSlot;
import com.tailor.engine.measure.AnchorMeasurer;
import com.tailor.engine.measure.PdfLines;
import com.tailor.engine.slots.Slot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P2-T8 (PHASE2_SPEC.md section 6): {@link Locker} on constructed {@link PdfLines.Line}
 * lists, no rendering. Covers the four cases in the PHASE2_SPEC.md 4.2 table.
 */
class LockerTest {

    @Test
    void lineSharedWithAnotherColumnLocksAsSharedLines() { // case 1
        Slot slot = supportedSlot(0, "Alpha bravo charlie");
        List<PdfLines.Line> lines = List.of(
                new PdfLines.Line(0, 100, "Alphabravocharlie" + "ForeignColumnText"));

        List<LockedSlot> result = Locker.lock(List.of(slot), lines);

        assertFalse(result.get(0).editable());
        assertEquals("shared_lines", result.get(0).lockReason());
    }

    @Test
    void foreignLineBetweenTwoOfABulletsLinesLocksAsSharedLines() { // case 2
        Slot slot = supportedSlot(0, "Alpha bravo charlie delta");
        List<PdfLines.Line> lines = List.of(
                new PdfLines.Line(0, 100, "Alphabravo"),
                new PdfLines.Line(0, 112, "ForeignColumnLineInBetween"),
                new PdfLines.Line(0, 124, "charliedelta"));

        List<LockedSlot> result = Locker.lock(List.of(slot), lines);

        assertFalse(result.get(0).editable());
        assertEquals("shared_lines", result.get(0).lockReason());
    }

    @Test
    void twoColumnsWithSeparateContiguousLinesAreBothEditableWithCorrectCounts() { // case 3
        // Each bullet spans 2 lines; AnchorMeasurer's anchor search needs >= 14 normalized
        // characters on a single line to find its starting prefix, so these mimic real wraps.
        Slot slotA = supportedSlot(0, "Aaaa bbbb cccc dddd eeee ffff gggg hhhh");
        Slot slotB = supportedSlot(1, "Iiii jjjj kkkk llll mmmm nnnn oooo pppp");
        List<PdfLines.Line> lines = List.of(
                new PdfLines.Line(0, 100, "Aaaabbbbccccdddd"),
                new PdfLines.Line(0, 112, "eeeeffffgggghhhh"),
                new PdfLines.Line(0, 124, "Iiiijjjjkkkkllll"),
                new PdfLines.Line(0, 136, "mmmmnnnnoooopppp"));

        List<LockedSlot> result = Locker.lock(List.of(slotA, slotB), lines);
        Map<Integer, Integer> counts = AnchorMeasurer.measure(lines, List.of(slotA.text(), slotB.text()));

        assertTrue(result.get(0).editable());
        assertTrue(result.get(1).editable());
        assertEquals(2, counts.get(0));
        assertEquals(2, counts.get(1));
    }

    @Test
    void ownBulletGlyphOnTheLineIsNotForeign() { // case 4
        Slot slot = supportedSlot(0, "Alpha bravo charlie");
        List<PdfLines.Line> lines = List.of(new PdfLines.Line(0, 100, "•Alphabravocharlie"));

        List<LockedSlot> result = Locker.lock(List.of(slot), lines);

        assertTrue(result.get(0).editable());
        assertEquals(null, result.get(0).lockReason());
    }

    private static Slot supportedSlot(int index, String text) {
        return new Slot(index, List.of(index), null, text, List.of(), true, List.of());
    }
}
