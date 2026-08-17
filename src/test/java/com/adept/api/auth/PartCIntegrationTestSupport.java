package com.adept.api.auth;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.frontend-base-url=http://localhost:3000",
    "app.public-api-base-url=http://localhost:8080",
    "app.email-from=Adept Test <test@adept.local>",
    "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.token-hash-pepper-base64=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
    "app.integration-encryption.active-key-version=1",
    "app.integration-encryption.keys[1]=CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
    "app.github.app-id=1",
    "app.github.app-slug=adept-test",
    "app.github.private-key-base64=dGVzdC1vbmx5",
    "app.github.webhook-secret=test-only",
    "app.jira.client-id=test-only",
    "app.jira.client-secret=test-only",
    "app.jira.callback-url=http://localhost/callback",
    "app.engine.base-url=http://localhost:8000",
    "app.engine.internal-token=test-only",
    "spring.mail.host=localhost",
    "spring.mail.port=1025",
    "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Import(PartCIntegrationTestSupport.MailCaptureConfiguration.class)
public abstract class PartCIntegrationTestSupport {

    protected static final String FRONTEND_ORIGIN = "http://localhost:3000";
    protected static final String VALID_PASSWORD = "violet-canoe-orbits-7296";
    private static final Pattern TOKEN_FRAGMENT =
        Pattern.compile("#token=([A-Za-z0-9_-]{43})");

    private static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("adept_part_c_test")
            .withUsername("adept")
            .withPassword("adept");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected CapturingMailSender mailSender;

    @BeforeEach
    protected void resetPartCDatabase() {
        mailSender.reset();
        jdbc.execute("TRUNCATE TABLE users, workspaces CASCADE");
    }

    protected static AccountRequestContext requestContext() {
        return new AccountRequestContext(
            "203.0.113.10",
            "Adept-Part-C-Test/1.0",
            UUID.randomUUID().toString()
        );
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "+" + UUID.randomUUID() + "@example.com";
    }

    protected static CsrfPair fetchCsrf(MockMvc mockMvc) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isNoContent())
            .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        if (cookie == null) {
            throw new AssertionError("Expected the CSRF endpoint to issue XSRF-TOKEN");
        }
        return new CsrfPair(cookie.getValue());
    }

    protected String awaitToken(String recipient, String subject) {
        CapturedMail message = mailSender.await(
            captured -> captured.hasRecipient(recipient) && Objects.equals(captured.subject(), subject),
            Duration.ofSeconds(5)
        );
        Matcher matcher = TOKEN_FRAGMENT.matcher(message.body());
        if (!matcher.find()) {
            throw new AssertionError("Expected a token fragment in captured mail");
        }
        return matcher.group(1);
    }

    protected record CsrfPair(String token) {

        public Cookie cookie() {
            return new Cookie("XSRF-TOKEN", token);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static final class MailCaptureConfiguration {

        @Bean
        @Primary
        CapturingMailSender capturingMailSender() {
            return new CapturingMailSender();
        }
    }

    public record CapturedMail(
        List<String> recipients,
        String subject,
        String body,
        String threadName
    ) {
        boolean hasRecipient(String recipient) {
            return recipients.contains(recipient);
        }
    }

    public static final class CapturingMailSender implements JavaMailSender {

        private final CopyOnWriteArrayList<CapturedMail> messages = new CopyOnWriteArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private volatile boolean fail;

        public void reset() {
            fail = false;
            attempts.set(0);
            messages.clear();
        }

        public void failSending(boolean shouldFail) {
            fail = shouldFail;
        }

        public List<CapturedMail> messages() {
            return List.copyOf(messages);
        }

        public void awaitAttempts(int expected, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (attempts.get() >= expected) {
                    return;
                }
                pause();
            }
            throw new AssertionError("Timed out waiting for mail delivery attempt");
        }

        public CapturedMail await(Predicate<CapturedMail> predicate, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (CapturedMail message : messages) {
                    if (predicate.test(message)) {
                        return message;
                    }
                }
                pause();
            }
            throw new AssertionError("Timed out waiting for captured mail");
        }

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            attempts.incrementAndGet();
            if (fail) {
                throw new MailSendException("simulated SMTP failure");
            }
            String[] recipients = simpleMessage.getTo();
            messages.add(new CapturedMail(
                recipients == null ? List.of() : List.of(recipients),
                simpleMessage.getSubject(),
                simpleMessage.getText(),
                Thread.currentThread().getName()
            ));
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            for (SimpleMailMessage message : simpleMessages) {
                send(message);
            }
        }

        @Override
        public MimeMessage createMimeMessage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(org.springframework.mail.javamail.MimeMessagePreparator preparator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(org.springframework.mail.javamail.MimeMessagePreparator... preparators) {
            throw new UnsupportedOperationException();
        }

        private static void pause() {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for captured mail", exception);
            }
        }
    }
}
