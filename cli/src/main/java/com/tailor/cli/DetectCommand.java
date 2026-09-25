package com.tailor.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tailor.engine.slots.DocxBulletDetection;
import com.tailor.engine.slots.EmphasisSpan;
import com.tailor.engine.slots.Slot;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code tailor detect <in.docx>} — spec section 4. Prints detected bullet
 * slots as JSON, in the same field shape as golden/*.json's "bullets" list
 * (minus the fields step 1.5 adds: lines, max_chars_prose, max_chars_tech).
 */
@Command(name = "detect", description = "Detect bullet slots and print them as JSON (spec section 4).")
public final class DetectCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Input .docx (normally already font-normalized)")
    private Path inPath;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SlotJson(
            int index,
            List<Integer> locator,
            String text,
            int chars,
            List<EmphasisSpan> emphasis,
            boolean supported,
            List<String> unsupportedReasons) {
    }

    @Override
    public Integer call() {
        try {
            List<Slot> slots = DocxBulletDetection.detect(inPath);
            List<SlotJson> out = slots.stream()
                    .map(s -> new SlotJson(
                            s.index(), s.locator(), s.text(), s.chars(),
                            s.emphasis(), s.supported(), s.unsupportedReasons()))
                    .toList();

            ObjectMapper mapper = new ObjectMapper()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(out));
            return 0;
        } catch (Exception e) {
            System.err.println("detect failed: " + e);
            return 1;
        }
    }
}
