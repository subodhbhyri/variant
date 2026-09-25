package com.tailor.engine.fonts;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loaded once from {@code fonts/font-map.json} (spec section 3.1). */
public final class FontMap {

    private final Map<String, String> map;
    private final Set<String> numberingGlyphKeep;
    private final Set<String> auditAllowed;

    private FontMap(Map<String, String> map, Set<String> numberingGlyphKeep, Set<String> auditAllowed) {
        this.map = map;
        this.numberingGlyphKeep = numberingGlyphKeep;
        this.auditAllowed = auditAllowed;
    }

    @SuppressWarnings("unchecked")
    public static FontMap loadDefault() {
        try (InputStream in = FontMap.class.getResourceAsStream("/fonts/font-map.json")) {
            if (in == null) {
                throw new IllegalStateException("fonts/font-map.json not on classpath");
            }
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(in, Map.class);
            Map<String, String> m = (Map<String, String>) root.get("map");
            Set<String> keep = new LinkedHashSet<>((List<String>) root.get("numberingGlyphKeep"));
            Set<String> allowed = new LinkedHashSet<>((List<String>) root.get("auditAllowed"));
            return new FontMap(Collections.unmodifiableMap(new LinkedHashMap<>(m)), keep, allowed);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load font-map.json", e);
        }
    }

    /** Font in the file -> font to render with. Empty if the file's font isn't in the map (leave as-is). */
    public Optional<String> replacement(String fontName) {
        return Optional.ofNullable(map.get(fontName));
    }

    /** Fonts kept as-is inside numbering.xml even though they're glyph-only fonts carrying a bullet dot. */
    public boolean isNumberingGlyphKeep(String fontName) {
        return numberingGlyphKeep.contains(fontName);
    }

    /** Base font names (subset prefix and style suffix stripped) allowed in a rendered PDF. */
    public Set<String> auditAllowed() {
        return auditAllowed;
    }
}
