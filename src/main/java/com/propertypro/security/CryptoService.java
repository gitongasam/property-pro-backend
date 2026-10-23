package com.propertypro.security;

import com.propertypro.config.AppProperties;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for secrets stored in bytea columns (ID numbers, bank accounts,
 * Daraja keys). Stored format: 12-byte IV followed by ciphertext + tag.
 * The key comes from the environment (app.crypto.key), never from the database.
 */
@Service
public class CryptoService {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(AppProperties props) {
        byte[] raw = Base64.getDecoder().decode(props.crypto().key());
        if (raw.length != 32) {
            throw new IllegalStateException("app.crypto.key must be 32 bytes, base64-encoded");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(byte[] stored) {
        if (stored == null || stored.length <= IV_BYTES) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(stored);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong app.crypto.key?)", e);
        }
    }

    /** One-way hash for duplicate checks on encrypted values (e.g. the same ID number twice). */
    public static String sha256(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Shows only the last four characters: •••• 4821. */
    public static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return value == null ? null : "••••";
        }
        return "•••• " + value.substring(value.length() - 4);
    }
}
