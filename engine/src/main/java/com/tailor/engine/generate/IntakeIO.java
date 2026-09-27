package com.tailor.engine.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads {@code intake.json} (PHASE4_SPEC.md section 1). */
public final class IntakeIO {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private IntakeIO() {
    }

    public static Intake load(Path intakeJson) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(intakeJson), Intake.class);
    }
}
