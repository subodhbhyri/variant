package com.tailor.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test doubles for the two things tests need to control: sent mail and time. */
@TestConfiguration
public class TestBeans {

    public static final class CapturingEmailSender implements EmailSender {
        public record Sent(String to, String link) {
            public String token() {
                return link.substring(link.indexOf("token=") + "token=".length());
            }
        }

        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public void sendSignInLink(String toEmail, String link) {
            sent.add(new Sent(toEmail, link));
        }

        public List<Sent> all() {
            return sent;
        }

        public Sent last() {
            return sent.get(sent.size() - 1);
        }

        public void clear() {
            sent.clear();
        }
    }

    public static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        public void advance(Duration d) {
            now = now.plus(d);
        }

        public void reset() {
            now = Instant.now();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Bean
    @Primary
    CapturingEmailSender capturingEmailSender() {
        return new CapturingEmailSender();
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
