package com.chengyi.xiewa.save.keys;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 从游戏 exe 中推导 DPAPI 的附加熵（entropy）。
 *
 * <p>游戏用 {@code CryptProtectData} 保护存档密钥时，除了当前 Windows 用户的凭据，
 * 还传了一段 32 字节自定义熵——它不写在存档里，而是藏在 exe 的两张常量表中，
 * 由一段"相加后异或"的运算合成。这里按原算法逐字节复现：
 *
 * <pre>
 *   for i in 0..15:
 *       ent[2i]   = ((table2[(0x12 + 0x1A*i - 0x0D) &amp; 0x1F] + key_i + 0xC5) &amp; 0xFF) ^ table1[2i]
 *       ent[2i+1] = ((table2[(0x12 + 0x1A*i) &amp; 0x1E]        + key_i)        &amp; 0xFF) ^ table1[2i+1]
 *       key_i = 0x3B + 0x76*i
 * </pre>
 *
 * <p>两张表各 32 字节，位于 exe 的固定文件偏移处。若游戏版本升级导致偏移变化，
 * 这里会抛出明确异常而不是解出乱码。
 */
public final class SaveEntropy {

    /** 第一张常量表在 exe 中的文件偏移。 */
    private static final long TABLE1_OFFSET = 0x502E5A0L;

    /** 第二张常量表在 exe 中的文件偏移。 */
    private static final long TABLE2_OFFSET = 0x502E5C0L;

    /** 每张表的字节数，同时也是熵的字节数。 */
    private static final int TABLE_SIZE = 32;

    private SaveEntropy() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 计算 DPAPI 附加熵。
     *
     * @param gameExe 游戏主程序路径
     * @return 32 字节熵
     * @throws SaveFormatException exe 读取失败或长度不足（版本不符）
     */
    public static byte[] compute(Path gameExe) throws SaveFormatException {
        byte[] table1;
        byte[] table2;
        try (RandomAccessFile file = new RandomAccessFile(gameExe.toFile(), "r")) {
            long length = file.length();
            if (length < TABLE2_OFFSET + TABLE_SIZE) {
                throw new SaveFormatException(
                        "游戏程序长度不足（%d 字节），不支持该版本：%s".formatted(length, gameExe));
            }
            table1 = new byte[TABLE_SIZE];
            table2 = new byte[TABLE_SIZE];
            file.seek(TABLE1_OFFSET);
            file.readFully(table1);
            file.seek(TABLE2_OFFSET);
            file.readFully(table2);
        } catch (IOException e) {
            throw new SaveFormatException("读取游戏程序失败：" + gameExe, e);
        }
        return mix(table1, table2);
    }

    /**
     * 双表混合运算。
     *
     * @param table1 第一张表（被异或的一方）
     * @param table2 第二张表（参与加法的一方）
     * @return 32 字节熵
     */
    static byte[] mix(byte[] table1, byte[] table2) {
        byte[] entropy = new byte[TABLE_SIZE];
        for (int i = 0; i < TABLE_SIZE / 2; i++) {
            int lowIndex = 2 * i;
            int spread = 0x12 + 0x1A * i;
            int salt = (0x3B + 0x76 * i) & 0xFF;

            int even = ((table2[(spread - 0x0D) & 0x1F] & 0xFF) + salt + 0xC5) & 0xFF;
            entropy[lowIndex] = (byte) (even ^ table1[lowIndex]);

            int odd = ((table2[spread & 0x1E] & 0xFF) + salt) & 0xFF;
            entropy[lowIndex + 1] = (byte) (odd ^ table1[lowIndex + 1]);
        }
        return entropy;
    }
}
