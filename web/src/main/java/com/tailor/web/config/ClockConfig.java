package com.tailor.web.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AppProperties.class, com.tailor.web.jobs.JobProperties.class})
public class ClockConfig {

    /** All time-dependent rules (link expiry, rate windows) read this clock, so tests can move it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
