package com.pulse.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256/GCM encryptor for tenant credential material stored in Postgres.
 * Uses the same key-derivation scheme as {@code GcpSecretManagerService} local-stub mode
 * for consistency. The encryption key is derived from a configurable seed.
 * <p>
 * Limitations: This is a local encryption mechanism suitable for dev/integration
 * environments. Production deployments should use GCP Secret Manager or an
 * equivalent KMS-backed solution. See test names for documented limitations.
 */
@Component
public class TenantCredentialEncryptor {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final String encryptionSeed;

    public TenantCredentialEncryptor(
            @Value("${pulse.gcp.local-stub-key:pulse-dev-local-stub-key-do-not-use-in-prod}")
            String encryptionSeed) {
        this.encryptionSeed = encryptionSeed;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Plaintext is required for encryption");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] bundle = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, bundle, 0, iv.length);
            System.arraycopy(encrypted, 0, bundle, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(bundle);
        } catch (Exception e) {
            throw new IllegalStateException("Credential encryption failed: " + e.getMessage(), e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("Ciphertext is required for decryption");
        }
        try {
            byte[] bundle = Base64.getDecoder().decode(ciphertext);
            if (bundle.length < GCM_IV_LENGTH) {
                throw new IllegalStateException("Encrypted credential is malformed");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(bundle, 0, iv, 0, GCM_IV_LENGTH);
            byte[] encrypted = new byte[bundle.length - GCM_IV_LENGTH];
            System.arraycopy(bundle, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Credential decryption failed: " + e.getMessage(), e);
        }
    }

    private SecretKeySpec deriveKey() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] key = sha.digest((encryptionSeed == null ? "" : encryptionSeed).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }
}
