package com.adept.api.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RsaKeyUtilsTest {

    @Test
    @DisplayName("Parses standard PKCS#8 PEM private key")
    void parsesPkcs8Pem() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        String base64Der = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64Der + "\n-----END PRIVATE KEY-----";

        PrivateKey parsed = RsaKeyUtils.parsePrivateKey(pem);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    @DisplayName("Parses Base64-encoded PKCS#8 DER private key")
    void parsesBase64EncodedDer() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        String base64Der = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        PrivateKey parsed = RsaKeyUtils.parsePrivateKey(base64Der);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    @DisplayName("Blank key throws IllegalArgumentException")
    void blankKeyThrows() {
        assertThatThrownBy(() -> RsaKeyUtils.parsePrivateKey(""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
