package com.chengyi.xiewa.save.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 存档用到的密码学原语。
 *
 * <p>三个约定必须与 Godot 的 {@code FileAccessEncrypted} 实现完全一致：
 * <ul>
 *   <li>加密算法 AES-256-<b>CFB128</b>（{@code segment_size=128}，不是 CFB8）</li>
 *   <li>MAC 密钥由存档密钥经 HKDF 派生：{@code HMAC-SHA256(saveKey, "xiewa-mac\x01")} 取前 32 字节</li>
 *   <li>文件完整性用明文 MD5 校验（写在 FAE 头里）</li>
 * </ul>
 */
public final class SaveCrypto {

    /** HKDF info 串，末尾的 0x01 是分隔符，不能省。 */
    private static final byte[] MAC_INFO = "xiewa-mac\u0001".getBytes(StandardCharsets.US_ASCII);

    /** MAC 密钥长度（字节）。 */
    public static final int MAC_KEY_LENGTH = 32;

    private SaveCrypto() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 由存档密钥派生 MAC 密钥。
     *
     * @param saveKey 32 字节存档密钥
     * @return 32 字节 MAC 密钥
     */
    public static byte[] deriveMacKey(byte[] saveKey) {
        byte[] full = hmacSha256(saveKey, MAC_INFO);
        return Arrays.copyOf(full, MAC_KEY_LENGTH);
    }

    /**
     * 计算 HMAC-SHA256。
     *
     * @param key  密钥
     * @param data 待签数据
     * @return 32 字节摘要
     */
    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前 JVM 不支持 HmacSHA256", e);
        }
    }

    /**
     * 计算 MD5。
     *
     * @param data 原始数据
     * @return 16 字节摘要
     */
    public static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 MD5", e);
        }
    }

    /**
     * AES-256-CFB128 解密。
     *
     * <p>注意：{@code "AES/CFB/NoPadding"} 在 JDK 中的默认分组就是 128 位，
     * 千万不要写成 {@code "AES/CFB8/NoPadding"}，那是 8 位分组，解出来是乱码。
     *
     * @param key        32 字节密钥
     * @param iv         16 字节初始向量
     * @param cipherText 密文，长度必须是 16 的整数倍
     * @return 明文，长度与密文一致（含补齐的零字节，由调用方按实际长度截断）
     */
    public static byte[] aesCfbDecrypt(byte[] key, byte[] iv, byte[] cipherText) {
        return aesCfb(Cipher.DECRYPT_MODE, key, iv, cipherText);
    }

    /**
     * AES-256-CFB128 加密。
     *
     * @param key       32 字节密钥
     * @param iv        16 字节初始向量
     * @param plainText 明文，长度必须是 16 的整数倍
     * @return 密文，长度与明文一致
     */
    public static byte[] aesCfbEncrypt(byte[] key, byte[] iv, byte[] plainText) {
        return aesCfb(Cipher.ENCRYPT_MODE, key, iv, plainText);
    }

    private static byte[] aesCfb(int mode, byte[] key, byte[] iv, byte[] input) {
        if (key.length != 32) {
            throw new IllegalArgumentException("密钥长度必须为 32 字节，实际 " + key.length);
        }
        if (iv.length != 16) {
            throw new IllegalArgumentException("IV 长度必须为 16 字节，实际 " + iv.length);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CFB 运算失败", e);
        }
    }
}
