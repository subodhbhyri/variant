package com.tailor.engine.blocks;

import com.tailor.engine.docx.DomUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * PHASE3_SPEC.md section 3 (reference: {@code parse_header}): extracts a header
 * paragraph's title/detail/links/date, or the reason it can't be swapped.
 */
public final class HeaderParser {

    private static final String LINK_PLACEHOLDER = "\u0000LINK\u0000";
    private static final Set<String> IGNORED_ELEMENTS = Set.of("pPr", "bookmarkStart", "bookmarkEnd", "proofErr");
    private static final Set<String> UNSUPPORTED_ELEMENTS = Set.of("fldSimple", "sdt", "smartTag", "ins", "del");

    public record LinkRef(String label) {
    }

    public record Parsed(String title, String detail, boolean detailIsList, List<LinkRef> links,
                          String date, String dateMode) {
    }

    /** Either {@link #parsed} is non-null and {@link #reason} is null, or vice versa. */
    public record Result(Parsed parsed, String reason) {
        public static Result ok(Parsed p) {
            return new Result(p, null);
        }

        public static Result fail(String reason) {
            return new Result(null, reason);
        }
    }

    private HeaderParser() {
    }

    public static Result parse(Element p) {
        List<String> parts = new ArrayList<>();
        List<LinkRef> links = new ArrayList<>();
        boolean[] tabSeen = {false};
        StringBuilder afterTab = new StringBuilder();

        for (Element el : DomUtil.elementChildren(p)) {
            String ln = el.getLocalName();
            if ("hyperlink".equals(ln)) {
                links.add(new LinkRef(DomUtil.allText(el).strip()));
                parts.add(LINK_PLACEHOLDER);
            } else if ("r".equals(ln)) {
                Node n = el.getFirstChild();
                while (n != null) {
                    if (n.getNodeType() == Node.ELEMENT_NODE) {
                        Element c = (Element) n;
                        String cln = c.getLocalName();
                        if ("tab".equals(cln) || "ptab".equals(cln)) {
                            tabSeen[0] = true;
                        } else if ("br".equals(cln)) {
                            return Result.fail("line_break_in_header");
                        } else if ("t".equals(cln)) {
                            String text = DomUtil.textContent(c);
                            if (tabSeen[0]) {
                                afterTab.append(text);
                            } else {
                                parts.add(text);
                            }
                        }
                    }
                    n = n.getNextSibling();
                }
            } else if (IGNORED_ELEMENTS.contains(ln)) {
                // skip
            } else if (UNSUPPORTED_ELEMENTS.contains(ln)) {
                return Result.fail("unsupported_element_in_header");
            }
            // any other unlisted element type is silently ignored, matching the reference
        }

        String body = String.join("", parts);
        String date = null;
        String dateMode = null;
        if (tabSeen[0]) {
            String afterTabStripped = afterTab.toString().strip();
            if (!DatePattern.DATE.matcher(afterTabStripped).find() && !afterTabStripped.isEmpty()) {
                return Result.fail("unrecognized_text_after_tab");
            }
            date = afterTabStripped.isEmpty() ? null : afterTabStripped;
            dateMode = "tab";
        } else {
            // The date search runs on link-placeholder-stripped text, but the endswith check and
            // the resulting truncation both use the *original* body (placeholders still in it) —
            // so a hyperlink trailing after where the date would be correctly prevents detection
            // (the rstripped original then ends with the placeholder marker, not the date).
            String bodyNoLinksRstrip = rstrip(body.replace(LINK_PLACEHOLDER, ""));
            String bodyOwnRstrip = rstrip(body);
            Matcher m = DatePattern.DATE.matcher(bodyNoLinksRstrip);
            if (m.find()) {
                String matched = m.group().strip();
                if (bodyOwnRstrip.endsWith(matched)) {
                    date = matched;
                    dateMode = "inline";
                    body = bodyOwnRstrip.substring(0, bodyOwnRstrip.length() - matched.length());
                }
            }
        }

        String core = body.replaceAll("\\s*[|\u00B7]?\\s*\u0000LINK\u0000", "").strip();
        if (!links.isEmpty()) {
            core = core.replace("[", "").replace("]", "").strip();
        }
        String[] pieces = core.split(" \\| ", -1);
        for (int i = 0; i < pieces.length; i++) {
            pieces[i] = pieces[i].strip();
        }
        String title = Vocab.stripChars(pieces[0], " |");
        String detail = null;
        if (pieces.length > 1) {
            String joined = String.join(" | ", java.util.Arrays.asList(pieces).subList(1, pieces.length));
            String stripped = Vocab.stripChars(joined, " |");
            detail = stripped.isEmpty() ? null : stripped;
        }
        if (title.isEmpty()) {
            return Result.fail("no_title");
        }
        String[] titleWords = title.split("\\s+");
        String lastWord = titleWords.length > 0 ? titleWords[titleWords.length - 1] : "";
        if (!lastWord.isEmpty() && DatePattern.URLISH.matcher(lastWord).matches()) {
            return Result.fail("bare_url_text");
        }

        boolean detailIsList = detail != null && detail.contains(",");
        return Result.ok(new Parsed(title, detail, detailIsList, links, date, dateMode));
    }

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
