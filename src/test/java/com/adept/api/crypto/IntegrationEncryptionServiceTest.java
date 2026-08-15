package com.adept.api.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegrationEncryptionServiceTest {

    private IntegrationEncryptionService encryptionService;
    private byte[] keyBytes1;
    private byte[] keyBytes2;

    @BeforeEach
    void setUp() {
        SecureRandom random = new SecureRandom();
        keyBytes1 = new byte[32];
        keyBytes2 = new byte[32];
        random.nextBytes(keyBytes1);
        random.nextBytes(keyBytes2);

        encryptionService = new IntegrationEncryptionService(
            1,
            Map.of(
                1, new SecretKeySpec(keyBytes1, "AES"),
                2, new SecretKeySpec(keyBytes2, "AES")
            ),
            random
        );
    }

    @Test
    @DisplayName("Encrypts and decrypts text with active key version")
    void encryptAndDecryptActiveKey() {
        String plaintext = "my-secret-access-token-12345";
        IntegrationEncryptionService.EncryptedPayload encrypted = encryptionService.encrypt(plaintext);

        assertThat(encrypted.keyVersion()).isEqualTo(1);
        assertThat(encrypted.ciphertext()).isNotBlank();
        assertThat(encrypted.ciphertext()).isNotEqualTo(plaintext);

        String decrypted = encryptionService.decrypt(encrypted.ciphertext(), encrypted.keyVersion());
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Supports decrypting data encrypted with an older key version")
    void supportsMultiVersionKeys() {
        String secretV1 = "secret-from-key-1";
        String secretV2 = "secret-from-key-2";

        IntegrationEncryptionService.EncryptedPayload encV1 = encryptionService.encrypt(secretV1, 1);
        IntegrationEncryptionService.EncryptedPayload encV2 = encryptionService.encrypt(secretV2, 2);

        assertThat(encryptionService.decrypt(encV1.ciphertext(), 1)).isEqualTo(secretV1);
        assertThat(encryptionService.decrypt(encV2.ciphertext(), 2)).isEqualTo(secretV2);
    }

    @Test
    @DisplayName("Tampered ciphertext fails authentication during decryption")
    void tamperedCiphertextFails() {
        String plaintext = "important-token";
        IntegrationEncryptionService.EncryptedPayload encrypted = encryptionService.encrypt(plaintext);

        byte[] raw = Base64.getDecoder().decode(encrypted.ciphertext());
        raw[raw.length - 1] ^= 0xFF; // Flip bits in authentication tag
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> encryptionService.decrypt(tampered, 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to decrypt");
    }

    @Test
    @DisplayName("Unknown key version throws IllegalArgumentException")
    void unknownKeyVersionThrows() {
        assertThatThrownBy(() -> encryptionService.decrypt("someCiphertext", 99))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown encryption key version");
    }
}
