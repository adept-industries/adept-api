package com.adept.api.crypto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public final class RsaKeyUtils {

    private static final byte[] RSA_ALGORITHM_IDENTIFIER = new byte[] {
        0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
        (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    private RsaKeyUtils() {
    }

    public static PrivateKey parsePrivateKey(String keyInput) {
        if (keyInput == null || keyInput.isBlank()) {
            throw new IllegalArgumentException("Private key must not be blank");
        }

        byte[] rawBytes;
        try {
            rawBytes = Base64.getDecoder().decode(keyInput.trim());
        } catch (IllegalArgumentException exception) {
            rawBytes = keyInput.getBytes(StandardCharsets.UTF_8);
        }

        String asText = new String(rawBytes, StandardCharsets.UTF_8);
        byte[] der;
        if (asText.contains("BEGIN")) {
            String cleaned = asText
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s+", "");
            der = Base64.getDecoder().decode(cleaned);
        } else {
            der = rawBytes;
        }

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            try {
                return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (InvalidKeySpecException pkcs8Failure) {
                byte[] pkcs8Der = wrapPkcs1ToPkcs8(der);
                return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
            }
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalArgumentException("Unable to parse RSA private key", exception);
        }
    }

    private static byte[] wrapPkcs1ToPkcs8(byte[] pkcs1Der) throws IOException {
        ByteArrayOutputStream octetStream = new ByteArrayOutputStream();
        octetStream.write(0x04);
        writeDerLength(octetStream, pkcs1Der.length);
        octetStream.write(pkcs1Der);
        byte[] octetString = octetStream.toByteArray();

        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(0x02);
        inner.write(0x01);
        inner.write(0x00);
        inner.write(RSA_ALGORITHM_IDENTIFIER);
        inner.write(octetString);
        byte[] innerBytes = inner.toByteArray();

        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        outer.write(0x30);
        writeDerLength(outer, innerBytes.length);
        outer.write(innerBytes);

        return outer.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else if (length < 65536) {
            out.write(0x82);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        } else {
            out.write(0x83);
            out.write((length >> 16) & 0xff);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
    }
}
