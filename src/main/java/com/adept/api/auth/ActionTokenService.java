package com.adept.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.adept.api.common.domain.ActionTokenPurpose;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.user.User;

@Service
public class ActionTokenService {

    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final UserActionTokenRepository tokenRepository;
    private final AppProperties properties;
    private final Clock clock;

    public ActionTokenService(
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            UserActionTokenRepository tokenRepository,
            AppProperties properties,
            Clock clock) {
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.tokenRepository = tokenRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedActionToken issue(User user, ActionTokenPurpose purpose) {
        String rawToken = tokenGenerator.generate();
        UserActionToken token = new UserActionToken();
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(hash(purpose, rawToken));
        token.setExpiresAt(clock.instant().plus(
            purpose == ActionTokenPurpose.VERIFY_EMAIL
                ? properties.auth().verificationTokenTtl()
                : properties.auth().resetTokenTtl()
        ));
        tokenRepository.save(token);
        return new IssuedActionToken(rawToken, token);
    }

    public void consumeActiveTokens(User user, ActionTokenPurpose purpose, Instant consumedAt) {
        List<UserActionToken> activeTokens = tokenRepository.findActiveByUserAndPurposeForUpdate(
            user.getId(),
            purpose
        );
        activeTokens.forEach(token -> token.setConsumedAt(consumedAt));
    }

    public String hash(ActionTokenPurpose purpose, String rawToken) {
        return purpose == ActionTokenPurpose.VERIFY_EMAIL
            ? tokenHasher.hashVerificationToken(rawToken)
            : tokenHasher.hashResetToken(rawToken);
    }

    public record IssuedActionToken(String rawToken, UserActionToken token) {
    }
}
