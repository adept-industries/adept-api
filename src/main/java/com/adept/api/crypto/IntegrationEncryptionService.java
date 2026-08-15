package com.adept.api.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.adept.api.config.AppProperties;

@Service
public class IntegrationEncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final int activeKeyVersion;
    private final Map<Integer, SecretKey> secretKeys;
    private final SecureRandom secureRandom;

    @Autowired
    public IntegrationEncryptionService(AppProperties properties) {
        this(
            properties.integrationEncryption().activeKeyVersion(),
            parseKeys(properties.integrationEncryption().keys()),
            new SecureRandom()
        );
    }

    public IntegrationEncryptionService(int activeKeyVersion, Map<Integer, SecretKey> secretKeys, SecureRandom secureRandom) {
        this.activeKeyVersion = activeKeyVersion;
        this.secretKeys = new ConcurrentHashMap<>(secretKeys);
        this.secureRandom = secureRandom;

        if (!this.secretKeys.containsKey(activeKeyVersion)) {
            throw new IllegalArgumentException(
                "Active integration encryption key version " + activeKeyVersion + " is not in keys map"
            );
        }
    }

    public EncryptedPayload encrypt(String plaintext) {
        return encrypt(plaintext, this.activeKeyVersion);
    }

    public EncryptedPayload encrypt(String plaintext, int keyVersion) {
        if (plaintext == null) {
            return null;
        }

        SecretKey key = secretKeys.get(keyVersion);
        if (key == null) {
            throw new IllegalArgumentException("Unknown encryption key version: " + keyVersion);
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            String base64Encrypted = Base64.getEncoder().encodeToString(buffer.array());
            return new EncryptedPayload(base64Encrypted, keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to encrypt integration payload", exception);
        }
    }

    public String decrypt(String base64Encrypted, int keyVersion) {
        if (base64Encrypted == null) {
            return null;
        }

        SecretKey key = secretKeys.get(keyVersion);
        if (key == null) {
            throw new IllegalArgumentException("Unknown encryption key version: " + keyVersion);
        }

        try {
            byte[] combined = Base64.getDecoder().decode(base64Encrypted);
            if (combined.length <= GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Invalid encrypted payload length");
            }

            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            buffer.get(iv);

            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to decrypt integration payload", exception);
        }
    }

    public int getActiveKeyVersion() {
        return activeKeyVersion;
    }

    private static Map<Integer, SecretKey> parseKeys(Map<Integer, String> keyStrings) {
        Map<Integer, SecretKey> keys = new ConcurrentHashMap<>();
        for (Map.Entry<Integer, String> entry : keyStrings.entrySet()) {
            byte[] decoded = Base64.getDecoder().decode(entry.getValue());
            keys.put(entry.getKey(), new SecretKeySpec(decoded, ALGORITHM));
        }
        return keys;
    }

    public record EncryptedPayload(String ciphertext, int keyVersion) {
    }
}
