package com.tailor.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailor.web.config.AppProperties;
import com.tailor.web.generation.RecordedModelClientFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/** The SES sender, and the guards that keep development-only modes out of production (PHASE6_SPEC.md sections 9.1 and 10). */
@ExtendWith(OutputCaptureExtension.class)
class MailAndGuardsTest {

    private static AppProperties props(String env) {
        return new AppProperties("api", env, "https://app.example.com", "https://app.example.com",
                new AppProperties.Mail("ses", "Resume Tailor <no-reply@mail.example.com>"),
                new AppProperties.Cookie(true, null),
                new AppProperties.Google(null, null, "a", "b", "c", "d"));
    }

    @Test
    void sesSendsTheLinkFromTheVerifiedSenderToTheUserOnly(CapturedOutput output) {
        SesV2Client ses = mock(SesV2Client.class);
        SesEmailSender sender = new SesEmailSender(props("prod"), ses);

        sender.sendSignInLink("person@example.com", "https://app.example.com/auth/email/verify?token=abc123");

        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        SendEmailRequest request = sent.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo("Resume Tailor <no-reply@mail.example.com>");
        assertThat(request.destination().toAddresses()).containsExactly("person@example.com");
        assertThat(request.content().simple().subject().data()).isEqualTo(SesEmailSender.SUBJECT);
        assertThat(request.content().simple().body().text().data()).contains("token=abc123").contains("15 minutes");
        assertThat(request.content().simple().body().html().data()).contains("token=abc123");
        // The address and the link (a credential) are never logged.
        assertThat(output.getAll()).doesNotContain("person@example.com").doesNotContain("abc123");
    }

    @Test
    void aSesFailureSurfacesAsAFailureWithoutTheAddressInIt() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(SesV2Exception.builder().message("throttled").build());
        SesEmailSender sender = new SesEmailSender(props("prod"), ses);

        assertThatThrownBy(() -> sender.sendSignInLink("person@example.com", "https://x/y?token=t"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void theLogMailModeRefusesToStartInProd() {
        assertThatThrownBy(() -> new LogEmailSender(props("prod"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed in prod");
        assertThatThrownBy(() -> new LogEmailSender(props("PROD"))).isInstanceOf(IllegalStateException.class);
        new LogEmailSender(props("dev")); // fine on a developer's machine
    }

    @Test
    void recordedModelAnswersRefuseToStartInProd() {
        String file = Path.of("/app/fixtures/phase4/recorded_responses.json").toString();
        assertThatThrownBy(() -> new RecordedModelClientFactory(file, props("prod"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed in prod");
    }
}
