package com.review.vt.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密：API Key 落库的唯一加密通道。
 *
 * <p>每次加密用全新 12 字节随机 nonce（GCM 的 nonce 绝不重复使用），
 * 密文格式 = Base64(nonce) + Base64(ciphertext||128bit-tag)，version 留给未来主密钥轮换。
 */
public class AesGcmCrypto {

    /** 当前密钥版本（users.api_key_version；轮换主密钥时递增并做全量重加密） */
    public static final int VERSION = 1;

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** nonce + 密文，落库到 users.api_key_nonce / users.api_key_cipher */
    public record Encrypted(String nonce, String cipherText) {}

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCrypto(SecretKey key) {
        this.key = key;
    }

    public Encrypted encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(ct));
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败：" + e.getMessage(), e);
        }
    }

    public String decrypt(String nonceB64, String cipherB64) {
        try {
            byte[] nonce = Base64.getDecoder().decode(nonceB64);
            byte[] ct = Base64.getDecoder().decode(cipherB64);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败（主密钥是否已更换？）：" + e.getMessage(), e);
        }
    }
}
