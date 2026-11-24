package com.chengyi.xiewa.save.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import com.chengyi.xiewa.save.format.VariantNavigator;

/**
 * 新物品条目的字节编码器。
 *
 * <p>Godot 的数组元素是自描述的 Variant，没有「预留空位」这回事。要在背包里塞一件新物品，
 * 唯一安全的做法是：<b>拿一件现有物品当模板，把其中的 uid / id / count 三个值换掉</b>，
 * 其余字段（品质、来源、绑定状态…）原样保留。这样既不需要了解物品结构的全部字段，
 * 也不会因为漏写字段导致游戏读档时崩。
 *
 * <p>关键在于「原地编码」：三个字段的新值必须与旧值占用<b>完全相同的字节数</b>，
 * 否则后续偏移全乱、签名必然对不上。整数统一按 4 字节写（超出 int32 才用 8 字节标记）。
 */
final class ItemTemplate {

    /** Variant 类型：整数。 */
    private static final int TYPE_INT = 2;

    /** Variant 类型：字符串。 */
    private static final int TYPE_STRING = 4;

    /** Variant 类型：字典（键值均为自描述元素，键/值共享类型标志位为 0）。 */
    private static final int TYPE_DICTIONARY = 27;

    /** 宽整数标志位（flags 的第 0 位）。 */
    private static final int FLAG_WIDE = 1 << 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ItemTemplate() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 克隆模板条目并替换 uid / id / count。
     *
     * @param navigator 指向完整明文的导航器
     * @param template  模板条目（通常是数组第一个元素）
     * @param newUid    新的唯一 ID
     * @param newId     新物品 ID
     * @param newCount  新数量
     * @return 编码后的元素字节
     */
    static byte[] cloneWith(VariantNavigator navigator, VariantNavigator.ArrayEntry template,
                            String newUid, int newId, long newCount) {
        int length = template.valueEndOffset() - template.elementOffset();
        byte[] element = navigator.copyBytes(template.elementOffset(), length);
        VariantNavigator elementNav = new VariantNavigator(element);
        List<VariantNavigator.DictEntry> entries = elementNav.dictEntries(0);
        if (entries.isEmpty()) {
            throw new IllegalStateException("模板条目不是字典，无法克隆");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(element.length + 32);
        int head = entries.get(0).keyOffset();
        out.write(element, 0, head);

        for (VariantNavigator.DictEntry entry : entries) {
            String key = String.valueOf(entry.key());
            byte[] keyBytes = Arrays.copyOfRange(element, entry.keyOffset(), entry.valueOffset());
            byte[] valueBytes = Arrays.copyOfRange(element, entry.valueOffset(), entry.valueEndOffset());
            switch (key) {
                case "uid" -> valueBytes = encodeString(newUid);
                case "id" -> valueBytes = encodeInt(newId);
                case "count" -> valueBytes = encodeInt(newCount);
                case "from_mall" -> {
                    // 商城标志一律清成 false（布尔值置零不改变字节宽度）：
                    // 核账只统计带 from_mall 的商城装备件数，克隆件必须以普通装备身份入包
                    Arrays.fill(valueBytes, (byte) 0);
                }
                default -> {
                    // 其余字段（品质、时间戳等）原样保留
                }
            }
            out.write(keyBytes, 0, keyBytes.length);
            out.write(valueBytes, 0, valueBytes.length);
        }
        return out.toByteArray();
    }

    /**
     * 从零编码一条极简装备条目 {@code {uid, id}}。
     *
     * <p>与游戏副本掉落落包的写法完全同款（{@code 装备列表.push_front({uid, id})}）：
     * 不带耐久、不带强化字段 —— 游戏读档时对缺失字段按默认值处理
     * （强化 0、耐久满），之后在游戏里正常强化即可。
     * 刻意不用现有条目当模板，避免把别人的强化等级/耐久一起继承过来。
     *
     * @param newUid 新的唯一 ID
     * @param newId  装备 ID
     * @return 编码后的字典 Variant 字节
     */
    static byte[] newEquipment(String newUid, int newId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(72);
        writeU32(out, TYPE_DICTIONARY);
        writeU32(out, 2);
        writeAll(out, encodeString("uid"));
        writeAll(out, encodeString(newUid));
        writeAll(out, encodeString("id"));
        writeAll(out, encodeInt(newId));
        return out.toByteArray();
    }

    /**
     * 生成一个物品唯一 ID（时间戳 + 随机后缀，格式与游戏一致）。
     *
     * @return uid 字符串
     */
    static String newUid() {
        long timePart = System.currentTimeMillis() % 10_000_000_000L;
        long randomPart = 2_147_483_648L + (RANDOM.nextLong() & 0x7FFFFFFFL);
        return timePart + "_" + randomPart;
    }

    /**
     * 编码一个字符串 Variant（类型头 + 长度 + 内容 + 4 字节对齐补零）。
     *
     * @param value 字符串
     * @return 完整 Variant 字节
     */
    static byte[] encodeString(String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        int padding = (4 - raw.length % 4) % 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 12);
        writeU32(out, TYPE_STRING);
        writeU32(out, raw.length);
        out.write(raw, 0, raw.length);
        for (int i = 0; i < padding; i++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    /**
     * 编码一个整数 Variant（32 位放得下就用 32 位，否则转 64 位）。
     *
     * @param value 整数
     * @return 完整 Variant 字节
     */
    static byte[] encodeInt(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            writeU32(out, TYPE_INT);
            writeU32(out, (int) value);
        } else {
            writeU32(out, TYPE_INT | FLAG_WIDE);
            writeU32(out, (int) value);
            writeU32(out, (int) (value >>> 32));
        }
        return out.toByteArray();
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeAll(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
