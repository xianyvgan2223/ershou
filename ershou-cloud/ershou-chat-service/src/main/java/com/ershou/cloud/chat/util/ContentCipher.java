package com.ershou.cloud.chat.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 聊天正文的对称加密（与旧单体 {@code com.ershou.campus.common.util.ContentCipher} 完全一致）。
 *
 * <p>AES/GCM + 随机 IV，输出 {@code v1:base64(iv||密文||tag)}：
 * 带版本前缀以区分"已加密文本"与"图片 URL / 卡片 JSON"这类不需要加密的内容。
 *
 * <p>⚠️ 迁移兼容：chat-service 必须使用与旧单体相同的 chat-key（默认 __REDACTED_CHAT_KEY__），
 * 否则从旧库迁过来的历史消息无法解密。密钥在 Nacos {@code ershou.crypto.chat-key} 配置。
 */
@Component
public class ContentCipher {

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ContentCipher(@Value("${ershou.crypto.chat-key:__REDACTED_CHAT_KEY__}") String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("聊天内容加密器初始化失败", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("聊天内容加密失败", e);
        }
    }

    /** @return 明文；非加密内容原样返回；解密失败返回 null */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS,
                    Arrays.copyOfRange(raw, 0, IV_LENGTH)));
            byte[] plain = cipher.doFinal(Arrays.copyOfRange(raw, IV_LENGTH, raw.length));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
