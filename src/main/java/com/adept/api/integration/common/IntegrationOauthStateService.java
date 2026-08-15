package com.adept.api.integration.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.ExternalProvider;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.crypto.IntegrationEncryptionService;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;

@Service
public class IntegrationOauthStateService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final IntegrationOauthStateRepository stateRepository;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final IntegrationEncryptionService encryptionService;
    private final Clock clock;

    public IntegrationOauthStateService(
            IntegrationOauthStateRepository stateRepository,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            IntegrationEncryptionService encryptionService,
            Clock clock) {
        this.stateRepository = stateRepository;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.encryptionService = encryptionService;
        this.clock = clock;
    }

    @Transactional
    public IssuedOauthState issueState(
            ExternalProvider provider,
            Workspace workspace,
            Membership initiatedBy,
            String codeVerifier,
            String redirectPath) {
        String rawState = tokenGenerator.generate();
        String stateHash = tokenHasher.hashIntegrationState(rawState);

        IntegrationOauthState oauthState = new IntegrationOauthState();
        oauthState.setProvider(provider);
        oauthState.setWorkspace(workspace);
        oauthState.setInitiatedBy(initiatedBy);
        oauthState.setStateHash(stateHash);
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            IntegrationEncryptionService.EncryptedPayload encryptedVerifier =
                encryptionService.encrypt(codeVerifier);
            // Format: version:ciphertext so we know which key version was used
            oauthState.setCodeVerifierEnc(encryptedVerifier.keyVersion() + ":" + encryptedVerifier.ciphertext());
        }
        oauthState.setRedirectPath(redirectPath != null && !redirectPath.isBlank() ? redirectPath : "/dashboard/integrations");
        oauthState.setExpiresAt(clock.instant().plus(STATE_TTL));

        stateRepository.save(oauthState);
        return new IssuedOauthState(rawState, oauthState);
    }

    @Transactional
    public ConsumedOauthState consumeState(ExternalProvider expectedProvider, String rawState) {
        if (rawState == null || rawState.isBlank()) {
            throw new ApiException(ProblemCode.INTEGRATION_STATE_INVALID, "State parameter is required");
        }

        String stateHash = tokenHasher.hashIntegrationState(rawState);
        IntegrationOauthState state = stateRepository.findActiveByStateHashForUpdate(stateHash, clock.instant())
            .orElseThrow(() -> new ApiException(
                ProblemCode.INTEGRATION_STATE_INVALID,
                "State is invalid, expired, or has already been used"
            ));

        if (state.getProvider() != expectedProvider) {
            throw new ApiException(ProblemCode.INTEGRATION_STATE_INVALID, "State provider mismatch");
        }

        state.setConsumedAt(clock.instant());

        String decryptedCodeVerifier = null;
        if (state.getCodeVerifierEnc() != null) {
            String encoded = state.getCodeVerifierEnc();
            int colonIndex = encoded.indexOf(':');
            if (colonIndex > 0) {
                int keyVersion = Integer.parseInt(encoded.substring(0, colonIndex));
                String ciphertext = encoded.substring(colonIndex + 1);
                decryptedCodeVerifier = encryptionService.decrypt(ciphertext, keyVersion);
            }
        }

        return new ConsumedOauthState(
            state.getWorkspace(),
            state.getInitiatedBy(),
            decryptedCodeVerifier,
            state.getRedirectPath()
        );
    }

    public record IssuedOauthState(String rawState, IntegrationOauthState state) {
    }

    public record ConsumedOauthState(
        Workspace workspace,
        Membership initiatedBy,
        String codeVerifier,
        String redirectPath
    ) {
    }
}
