package com.chengyi.xiewa.save.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 1.0.9（存档头 {@code mac_v = 2}）起启用的签名方案。
 *
 * <p>算法来自游戏内嵌的原生模块 {@code XiewaGuard}（版本串 {@code 1.3.1.2-audit}）。
 * 该模块导出的 {@code sign2(name, key, body)} 按下面两步算：
 *
 * <pre>
 *   prk = HMAC-SHA256(mask32, "xw2|" + name + "|" + key)
 *   sig = HMAC-SHA256(prk,   body)
 * </pre>
 *
 * <p>其中 {@code mask32} 是模块内的 32 字节常量，由一个三表轮转的函数现场生成
 * （见 {@link #MASK}）。存档签名用的 {@code name} 是字面量 {@code "save5"}，
 * {@code key} 是存档密钥，{@code body} 是 header 段与 payload 段的值字节拼接
 * （都不含各自的 Variant 类型标记）。
 *
 * <p>实测校验：对同一份存档，本实现算出的签名与游戏写入磁盘的签名逐字节相同。
 */
public final class GuardMac {

    /** 存档签名用的域标签。1.0.9 的 {@code local_save_manager.gdc} 里写死的字面量。 */
    public static final String SAVE_DOMAIN = "save5";

    /** 前缀，原生模块用 {@code "xw2|" + name + "|" + key} 拼 PRK 的输入。 */
    private static final byte[] PREFIX = "xw2|".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] SEPARATOR = "|".getBytes(StandardCharsets.US_ASCII);

    /** mask 生成器用到的三张 32 字节常量表，均取自模块的 .rdata。 */
    private static final byte[] T1 = hex(
            "5f813ea64b687ee96152fb8455c27c7f4df4dd033cfd01471906268509525bec");
    private static final byte[] T2 = hex(
            "0a1acbb697191b18d5d7cd1da8ccfae31a6bea6b5f8a4fa3e6837fd3aeea00ac");
    private static final byte[] T3 = hex(
            "dbf686726d59561668dc822ebb4a3ab779cad703c0e8073ac857ac524342b52e");

    /**
     * PRK 的 HMAC 密钥，由模块内联循环现场生成。
     *
     * <p>逆出来的循环体（i 从 0 到 31）：
     * <pre>
     *   a = (9  + 11i) &amp; 31   → T1 下标
     *   b = (11 +  7i) &amp; 31   → T3 下标
     *   c = (3  +  5i) &amp; 31   → T2 下标
     *   d = (0x5a + 0x17i) &amp; 0xff
     *   mask[i] = ((T1[a] * 45 + d) &amp; 0xff) ^ T3[b] ^ T2[c]
     * </pre>
     */
    public static final byte[] MASK = buildMask();

    private GuardMac() {
        throw new AssertionError("工具类不允许实例化");
    }

    private static byte[] buildMask() {
        byte[] mask = new byte[32];
        for (int i = 0; i < 32; i++) {
            int a = (9 + 11 * i) & 0x1F;
            int b = (11 + 7 * i) & 0x1F;
            int c = (3 + 5 * i) & 0x1F;
            int d = (0x5A + 0x17 * i) & 0xFF;
            int value = ((T1[a] & 0xFF) * 45 + d) & 0xFF;
            mask[i] = (byte) (value ^ T3[b] ^ T2[c]);
        }
        return mask;
    }

    /**
     * 计算 PRK：{@code HMAC-SHA256(mask32, "xw2|" + name + "|" + key)}。
     *
     * @param name 域标签，存档用 {@link #SAVE_DOMAIN}
     * @param key  原始密钥
     * @return 32 字节中间密钥
     */
    public static byte[] prk(String name, byte[] key) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] info = new byte[PREFIX.length + nameBytes.length + SEPARATOR.length + key.length];
        int at = 0;
        System.arraycopy(PREFIX, 0, info, at, PREFIX.length);
        at += PREFIX.length;
        System.arraycopy(nameBytes, 0, info, at, nameBytes.length);
        at += nameBytes.length;
        System.arraycopy(SEPARATOR, 0, info, at, SEPARATOR.length);
        at += SEPARATOR.length;
        System.arraycopy(key, 0, info, at, key.length);
        return SaveCrypto.hmacSha256(MASK, info);
    }

    /**
     * 计算签名：{@code HMAC-SHA256(prk(name, key), body)}。
     *
     * @param name 域标签
     * @param key  原始密钥
     * @param body 待签数据
     * @return 32 字节签名
     */
    public static byte[] sign(String name, byte[] key, byte[] body) {
        return SaveCrypto.hmacSha256(prk(name, key), body);
    }

    /**
     * 用存档域标签计算签名。
     *
     * @param saveKey 32 字节存档密钥
     * @param body    header 段 + payload 段的值字节
     * @return 32 字节签名
     */
    public static byte[] signSave(byte[] saveKey, byte[] body) {
        return sign(SAVE_DOMAIN, saveKey, body);
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 便于自检：把 mask 以十六进制返回。 */
    public static String maskHex() {
        StringBuilder sb = new StringBuilder();
        for (byte b : MASK) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 便于自检：判断 mask 是否与逆向结果一致。 */
    public static boolean maskLooksSane() {
        return Arrays.equals(MASK, hex(
                "5cff9f8f9a14ea70d082fb8a0a2b5745e1707d0f4cdb8765b6884a051c5a524f"));
    }
}
