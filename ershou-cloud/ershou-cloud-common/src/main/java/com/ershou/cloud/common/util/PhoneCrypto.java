package com.ershou.cloud.common.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 手机号加密工具（与单体 {@code com.ershou.campus.common.util.PhoneCrypto} 同算法）：
 * AES/ECB 存密文 + SHA-256 哈希做唯一索引；这样从旧库迁移过来的用户能被识别为同一人。
 *
 * <p>由 {@code CloudCommonAutoConfiguration} 用 {@code ershou.crypto.phone-key} 构造 Bean；
 * 服务直接 {@code @Autowired PhoneCrypto} 使用。
 */
public class PhoneCrypto {

    private final SecretKeySpec keySpec;

    public PhoneCrypto(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ershou.crypto.phone-key 未配置（见 .env 的 PHONE_CRYPTO_KEY）");
        }
        byte[] raw = new byte[16];
        byte[] src = key.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, raw, 0, Math.min(src.length, 16));
        this.keySpec = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("手机号加密失败", e);
        }
    }

    public String hash(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("手机号哈希失败", e);
        }
    }

    public String decrypt(String cipherText) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("手机号解密失败", e);
        }
    }

    /** 供展示用的脱敏手机号 */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
