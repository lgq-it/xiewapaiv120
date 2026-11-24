package com.chengyi.xiewa.save.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * FAE 容器编解码（Godot {@code FileAccessEncrypted} 的落盘格式）。
 *
 * <p>布局（小端）：
 * <pre>
 * ┌────────┬──────────────┬────────────┬────────┬──────────────┐
 * │ GDEC   │ MD5(明文)     │ 明文长度    │ IV     │ 密文          │
 * │ 4 字节  │ 16 字节       │ u64 / 8 字节│ 16 字节 │ align16(len) │
 * └────────┴──────────────┴────────────┴────────┴──────────────┘
 * </pre>
 *
 * <p>明文长度不一定对齐 16，写入时会补零后整体加密；读取时先按对齐长度解密，
 * 再按头里的真实长度截断，最后用头里的 MD5 校验密钥是否正确。
 */
public final class FaeCodec {

    /** 魔数。 */
    private static final byte[] MAGIC = {'G', 'D', 'E', 'C'};

    /** 固定头长度：魔数(4) + MD5(16) + 长度(8) + IV(16)。 */
    public static final int HEADER_SIZE = 44;

    /** MD5 在头中的偏移。 */
    private static final int OFFSET_MD5 = 4;

    /** 明文长度在头中的偏移。 */
    private static final int OFFSET_LENGTH = 20;

    /** IV 在头中的偏移。 */
    private static final int OFFSET_IV = 28;

    private static final SecureRandom RANDOM = new SecureRandom();

    private FaeCodec() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 解密存档文件内容。
     *
     * @param raw 磁盘上的原始字节
     * @param key 32 字节存档密钥
     * @return 明文载荷
     * @throws SaveFormatException 魔数不符、长度越界或 MD5 校验失败（通常是密钥不对）
     */
    public static byte[] decode(byte[] raw, byte[] key) throws SaveFormatException {
        if (raw.length < HEADER_SIZE) {
            throw new SaveFormatException(
                    "文件太短（%d 字节），不是有效存档".formatted(raw.length));
        }
        if (!startsWithMagic(raw)) {
            throw new SaveFormatException("缺少 GDEC 魔数，不是加密存档或已被破坏");
        }

        byte[] expectedMd5 = Arrays.copyOfRange(raw, OFFSET_MD5, OFFSET_MD5 + 16);
        long declaredLength = ByteBuffer.wrap(raw, OFFSET_LENGTH, Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong();
        if (declaredLength < 0 || declaredLength > Integer.MAX_VALUE - 16) {
            throw new SaveFormatException("存档声明的明文长度异常：" + declaredLength);
        }
        byte[] iv = Arrays.copyOfRange(raw, OFFSET_IV, OFFSET_IV + 16);

        int plainLength = (int) declaredLength;
        int cipherLength = align16(plainLength);
        if (raw.length < HEADER_SIZE + cipherLength) {
            throw new SaveFormatException(
                    "文件被截断：需要 %d 字节密文，实际只有 %d 字节"
                            .formatted(cipherLength, raw.length - HEADER_SIZE));
        }

        byte[] cipherText = Arrays.copyOfRange(raw, HEADER_SIZE, HEADER_SIZE + cipherLength);
        byte[] decrypted = SaveCrypto.aesCfbDecrypt(key, iv, cipherText);
        byte[] plain = Arrays.copyOf(decrypted, plainLength);

        if (!Arrays.equals(SaveCrypto.md5(plain), expectedMd5)) {
            throw new SaveFormatException("MD5 校验失败——存档密钥不正确，或存档已损坏");
        }
        return plain;
    }

    /**
     * 加密并打包成 FAE 格式。
     *
     * @param plain 明文载荷
     * @param key   32 字节存档密钥
     * @param iv    16 字节初始向量；传 {@code null} 则随机生成
     * @return 可直接落盘的字节
     */
    public static byte[] encode(byte[] plain, byte[] key, byte[] iv) {
        byte[] actualIv = (iv == null) ? randomIv() : iv;
        if (actualIv.length != 16) {
            throw new IllegalArgumentException("IV 长度必须为 16 字节");
        }

        byte[] padded = Arrays.copyOf(plain, align16(plain.length));
        byte[] cipherText = SaveCrypto.aesCfbEncrypt(key, actualIv, padded);

        ByteBuffer out = ByteBuffer.allocate(HEADER_SIZE + cipherText.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC);
        out.put(SaveCrypto.md5(plain));
        out.putLong(plain.length);
        out.put(actualIv);
        out.put(cipherText);
        return out.array();
    }

    /** 生成一个随机 IV。 */
    public static byte[] randomIv() {
        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        return iv;
    }

    /** 上取整到 16 的倍数。 */
    private static int align16(int value) {
        return (value + 15) / 16 * 16;
    }

    private static boolean startsWithMagic(byte[] raw) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
