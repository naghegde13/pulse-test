package com.pulse.auth.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantCredentialEncryptorTest {

    private final TenantCredentialEncryptor encryptor =
            new TenantCredentialEncryptor("test-encryption-seed");

    @Test
    void encryptDecrypt_roundTrip() {
        String original = "{\"private_key\": \"redacted-test-key-material\"}";
        String encrypted = encryptor.encrypt(original);
        String decrypted = encryptor.decrypt(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void encrypt_producesBase64Output() {
        String encrypted = encryptor.encrypt("test-data");
        assertNotNull(encrypted);
        assertFalse(encrypted.isBlank());
        // Base64 only contains [A-Za-z0-9+/=]
        assertTrue(encrypted.matches("[A-Za-z0-9+/=]+"));
    }

    @Test
    void encrypt_differentIvEachTime() {
        String a = encryptor.encrypt("same-plaintext");
        String b = encryptor.encrypt("same-plaintext");
        // AES-GCM uses random IV so ciphertexts differ even for same plaintext
        assertNotEquals(a, b);
    }

    @Test
    void encrypt_ciphertextDoesNotContainPlaintext() {
        String secret = "redacted-test-key-material\nMIIEowIBAAKCAQ";
        String encrypted = encryptor.encrypt(secret);
        assertFalse(encrypted.contains("redacted-test-key-material"));
        assertFalse(encrypted.contains("MIIEowIBAAKCAQ"));
    }

    @Test
    void decrypt_wrongSeed_fails() {
        String encrypted = encryptor.encrypt("test-data");
        TenantCredentialEncryptor wrongKey = new TenantCredentialEncryptor("wrong-seed");
        assertThrows(IllegalStateException.class, () -> wrongKey.decrypt(encrypted));
    }

    @Test
    void encrypt_nullInput_throws() {
        assertThrows(IllegalArgumentException.class, () -> encryptor.encrypt(null));
    }

    @Test
    void encrypt_blankInput_throws() {
        assertThrows(IllegalArgumentException.class, () -> encryptor.encrypt(""));
    }

    @Test
    void decrypt_nullInput_throws() {
        assertThrows(IllegalArgumentException.class, () -> encryptor.decrypt(null));
    }

    @Test
    void decrypt_blankInput_throws() {
        assertThrows(IllegalArgumentException.class, () -> encryptor.decrypt(""));
    }

    @Test
    void decrypt_malformedInput_throws() {
        assertThrows(IllegalStateException.class, () -> encryptor.decrypt("short"));
    }

    /**
     * Documents limitation: this is local AES encryption, not KMS-backed.
     * Suitable for dev/integration; production should use GCP Secret Manager or KMS.
     */
    @Test
    void localEncryptionLimitation_documentedInTestName() {
        // This test exists to document the limitation. The encryption is
        // deterministic on seed — key rotation requires re-encryption.
        String encrypted = encryptor.encrypt("sensitive-data");
        assertNotNull(encrypted);
    }
}
