package com.tailor.engine.gate;

import java.util.Map;

/** User-facing text for each reason code (PHASE2_SPEC.md 2.1.2). */
public final class GateMessages {

    private static final Map<String, String> FIXED = Map.ofEntries(
            Map.entry(GateReason.FILE_TOO_LARGE,
                    "Your file is over 2 MB. Save it without embedded images or fonts and try again."),
            Map.entry(GateReason.ENCRYPTED_OR_LEGACY,
                    "This file is password-protected or in the old .doc format. Remove the password or save it as .docx."),
            Map.entry(GateReason.NOT_A_DOCX, "This doesn't look like a Word .docx file."),
            Map.entry(GateReason.TOO_MANY_ENTRIES,
                    "We couldn't safely read this file. Try saving it again from Word as a new .docx."),
            Map.entry(GateReason.DUPLICATE_ENTRY,
                    "We couldn't safely read this file. Try saving it again from Word as a new .docx."),
            Map.entry(GateReason.UNSAFE_PATH,
                    "We couldn't safely read this file. Try saving it again from Word as a new .docx."),
            Map.entry(GateReason.ZIP_BOMB,
                    "We couldn't safely read this file. Try saving it again from Word as a new .docx."),
            Map.entry(GateReason.UNSAFE_XML,
                    "We couldn't safely read this file. Try saving it again from Word as a new .docx."),
            Map.entry(GateReason.MACROS,
                    "This file contains macros. Save it as a plain .docx (not .docm) and try again."),
            Map.entry(GateReason.EMBEDDED_OBJECT,
                    "This file contains embedded objects. Remove them and try again."),
            Map.entry(GateReason.EXTERNAL_RESOURCE,
                    "This file loads content from the internet (for example a linked image). "
                            + "Insert the image directly and try again."),
            Map.entry(GateReason.TRACKED_CHANGES,
                    "Your resume has tracked changes. Accept or reject them in Word, then upload again."),
            Map.entry(GateReason.TOO_MANY_PAGES, "Resumes longer than 2 pages aren't supported yet."));

    private GateMessages() {
    }

    /** For every reason except TOO_FEW_EDITABLE and NEEDS_USER, which carry a page/count parameter. */
    public static String forReason(String reason) {
        String message = FIXED.get(reason);
        if (message == null) {
            throw new IllegalArgumentException("no fixed message for reason: " + reason);
        }
        return message;
    }

    public static String tooFewEditable(int editableCount) {
        return "We could only find " + editableCount
                + " bullet points we can safely edit. Tailoring needs at least 3.";
    }

    public static String needsUser(int pages) {
        return "Your resume doesn't fit on " + pages + " page(s) with our fonts. (Preview offers options.)";
    }
}
