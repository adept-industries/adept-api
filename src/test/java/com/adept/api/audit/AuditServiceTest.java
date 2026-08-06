package com.adept.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.adept.api.config.AppProperties;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.support.TestAppProperties;
import com.adept.api.user.User;

class AuditServiceTest {

    @Test
    void auditHashesIpAndUserAgentBeforeSaving() {
        AtomicReference<AuditLog> saved = new AtomicReference<>();
        AuditLogRepository repository = (AuditLogRepository) Proxy.newProxyInstance(
            AuditLogRepository.class.getClassLoader(),
            new Class<?>[] { AuditLogRepository.class },
            (proxy, method, args) -> {
                if (method.getName().equals("save")) {
                    saved.set((AuditLog) args[0]);
                    return args[0];
                }
                return null;
            }
        );
        AppProperties properties = TestAppProperties.create();
        AuditService service = new AuditService(repository, new TokenHasher(properties));
        User user = new User();
        user.setId(java.util.UUID.randomUUID());

        service.record(
            AuditAction.ACCOUNT_SIGNUP,
            user,
            null,
            null,
            "USER",
            user.getId(),
            java.util.Map.of(),
            "203.0.113.10",
            "Browser/1.0"
        );

        assertThat(saved.get().getIpHash()).matches("^[0-9a-f]{64}$");
        assertThat(saved.get().getUserAgent()).matches("^[0-9a-f]{64}$");
        assertThat(saved.get().getUserAgent()).doesNotContain("Browser");
    }

    @Test
    void auditRemovesEveryControlCharacterAndCapsUserAgentBeforeHashing() {
        AtomicReference<AuditLog> saved = new AtomicReference<>();
        AuditLogRepository repository = capturingRepository(saved);
        AppProperties properties = TestAppProperties.create();
        TokenHasher hasher = new TokenHasher(properties);
        AuditService service = new AuditService(repository, hasher);
        User user = new User();
        user.setId(java.util.UUID.randomUUID());
        String bounded = "Browser/1.0" + "x".repeat(501);
        String supplied = "\r\n\t\u0000Browser/1.0" + "x".repeat(600);

        service.record(
            AuditAction.ACCOUNT_SIGNUP,
            user,
            null,
            null,
            "USER",
            user.getId(),
            java.util.Map.of(),
            null,
            supplied
        );

        assertThat(saved.get().getUserAgent()).isEqualTo(hasher.hashUserAgent(bounded));
    }

    private static AuditLogRepository capturingRepository(AtomicReference<AuditLog> saved) {
        return (AuditLogRepository) Proxy.newProxyInstance(
            AuditLogRepository.class.getClassLoader(),
            new Class<?>[] { AuditLogRepository.class },
            (proxy, method, args) -> {
                if (method.getName().equals("save")) {
                    saved.set((AuditLog) args[0]);
                    return args[0];
                }
                return null;
            }
        );
    }
}
