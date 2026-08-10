package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;

@ExtendWith(OutputCaptureExtension.class)
class SensitiveLoggingTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void logsContainNoSensitiveSecretsPasswordsOrRawTokens(CapturedOutput output) throws Exception {
        String email = uniqueEmail("sensitive-log");
        String password = "UltraSecretPassword123!";

        SignupResponse signup = authService.signup(
            new SignupRequest(email, password, "Secret Log User", "Log Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf = fetchCsrf(mockMvc);

        mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, password)))
            .andExpect(status().isOk());

        String logs = output.getAll();
        assertThat(logs).doesNotContain(password);
        assertThat(logs).doesNotContain("adept_refresh=");
        assertThat(logs).doesNotContain("Bearer ");
    }
}
