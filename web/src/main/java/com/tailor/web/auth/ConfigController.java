package com.tailor.web.auth;

import com.tailor.web.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** PHASE6_SPEC.md section 4.1: which sign-in methods are on, so the frontend shows only those. */
@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class ConfigController {

    public record PublicConfig(List<String> signin) {
    }

    private final AppProperties props;

    public ConfigController(AppProperties props) {
        this.props = props;
    }

    @Operation(summary = "Public, no session: the sign-in methods that are switched on (\"google\", \"email\").",
            operationId = "getConfig")
    @ApiResponse(responseCode = "200", description = "The enabled sign-in methods.")
    @GetMapping("/config")
    public PublicConfig config() {
        List<String> methods = new ArrayList<>();
        if (props.google().enabled()) {
            methods.add("google");
        }
        if (props.mail().enabled()) {
            methods.add("email");
        }
        return new PublicConfig(methods);
    }
}
