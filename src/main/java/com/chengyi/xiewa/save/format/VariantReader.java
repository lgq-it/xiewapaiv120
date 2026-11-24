package com.chengyi.xiewa.save.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Variant 字节流解析成 Java 值对象。
 *
 * <p>类型映射：
 * <table border="1">
 *   <caption>Variant 到 Java 的映射</caption>
 *   <tr><th>Variant</th><th>Java</th></tr>
 *   <tr><td>Nil</td><td>{@code null}</td></tr>
 *   <tr><td>Bool</td><td>{@link Boolean}</td></tr>
 *   <tr><td>Int（32/64 位）</td><td>{@link Long}</td></tr>
 *   <tr><td>Float（32/64 位）</td><td>{@link Double}</td></tr>
 *   <tr><td>String / StringName</td><td>{@link String}</td></tr>
 *   <tr><td>Dictionary</td><td>{@link LinkedHashMap}（保持写入顺序）</td></tr>
 *   <tr><td>Array</td><td>{@link ArrayList}</td></tr>
 *   <tr><td>PackedByteArray</td><td>{@code byte[]}</td></tr>
 *   <tr><td>Packed*Array</td><td>{@code long[]} / {@code double[]}</td></tr>
 *   <tr><td>Vector2 ~ Color</td><td>{@code long[]} / {@code double[]}（4 字节分量）</td></tr>
 * </table>
 *
 * <p>整型一律归一为 {@code Long}，浮点一律归一为 {@code Double}，避免调用方到处判断位宽。
 */
public final class VariantReader {

    /** 解析深度上限，防止损坏数据造成栈溢出。 */
    private static final int MAX_DEPTH = 64;

    private final VariantNavigator navigator;

    /**
     * @param navigator 已绑定到目标字节数组的导航器
     */
    public VariantReader(VariantNavigator navigator) {
        this.navigator = navigator;
    }

    /**
     * 解析指定偏移处的 Variant。
     *
     * @param offset Variant 起始偏移
     * @return 对应的 Java 值
     */
    public Object read(int offset) {
        return read(offset, 0);
    }

    /**
     * 带深度保护的递归解析。
     *
     * @param offset Variant 起始偏移
     * @param depth  当前递归层数
     * @return 对应的 Java 值
     */
    private Object read(int offset, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Variant 嵌套超过 " + MAX_DEPTH + " 层，数据可能已损坏");
        }
        VariantSpan span = navigator.spanAt(offset);
        return switch (span.type()) {
            case VariantTypes.NIL -> null;
            case VariantTypes.BOOL -> navigator.readU8(span.valueOffset()) != 0;
            case VariantTypes.INT -> span.isWideNumeric()
                    ? navigator.readI64(span.valueOffset())
                    : (long) navigator.readI32(span.valueOffset());
            case VariantTypes.FLOAT -> span.isWideNumeric()
                    ? navigator.readF64(span.valueOffset())
                    : (double) navigator.readF32(span.valueOffset());
            case VariantTypes.STRING, VariantTypes.STRING_NAME ->
                    navigator.readString(span.valueOffset());
            case VariantTypes.DICTIONARY -> readDictionary(offset, depth);
            case VariantTypes.ARRAY -> readArray(offset, depth);
            case VariantTypes.PACKED_BYTE_ARRAY ->
                    navigator.copyBytes(span.valueOffset(), span.valueLength());
            case VariantTypes.PACKED_INT32_ARRAY -> readInt32Array(span);
            case VariantTypes.PACKED_INT64_ARRAY -> readInt64Array(span);
            case VariantTypes.PACKED_FLOAT32_ARRAY -> readFloat32Array(span);
            case VariantTypes.PACKED_FLOAT64_ARRAY -> readFloat64Array(span);
            default -> readFixedNumeric(span);
        };
    }

    /**
     * 解析字典：键按原样读出（存档里都是字符串），值递归解析。
     *
     * <p>用 {@link LinkedHashMap} 而不是 HashMap——物品字典里的字段顺序
     * 会对齐调试输出，保持写入顺序读起来更像原始数据。
     *
     * @param offset 字典 Variant 起始偏移
     * @param depth  当前递归层数
     * @return 有序映射
     */
    private Map<Object, Object> readDictionary(int offset, int depth) {
        List<VariantNavigator.DictEntry> entries = navigator.dictEntries(offset);
        Map<Object, Object> map = new LinkedHashMap<>(Math.max(16, entries.size() * 2));
        for (VariantNavigator.DictEntry entry : entries) {
            map.put(entry.key(), read(entry.valueOffset(), depth + 1));
        }
        return map;
    }

    /**
     * 解析数组：逐元素递归解析，顺序与存档一致（下标就是界面上的位置）。
     *
     * @param offset 数组 Variant 起始偏移
     * @param depth  当前递归层数
     * @return 列表
     */
    private List<Object> readArray(int offset, int depth) {
        List<VariantNavigator.ArrayEntry> entries = navigator.arrayEntries(offset);
        List<Object> list = new ArrayList<>(entries.size());
        for (VariantNavigator.ArrayEntry entry : entries) {
            list.add(read(entry.elementOffset(), depth + 1));
        }
        return list;
    }

    /**
     * 解析 PackedInt32Array。
     *
     * @param span 值区间
     * @return 逐元素提升为 long 的数组
     */
    private long[] readInt32Array(VariantSpan span) {
        int count = span.valueLength() / 4;
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            values[i] = navigator.readI32(span.valueOffset() + i * 4);
        }
        return values;
    }

    /**
     * 解析 PackedInt64Array。
     *
     * @param span 值区间
     * @return 数组
     */
    private long[] readInt64Array(VariantSpan span) {
        int count = span.valueLength() / 8;
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            values[i] = navigator.readI64(span.valueOffset() + i * 8);
        }
        return values;
    }

    /**
     * 解析 PackedFloat32Array。
     *
     * @param span 值区间
     * @return 逐元素提升为 double 的数组
     */
    private double[] readFloat32Array(VariantSpan span) {
        int count = span.valueLength() / 4;
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = navigator.readF32(span.valueOffset() + i * 4);
        }
        return values;
    }

    /**
     * 解析 PackedFloat64Array。
     *
     * @param span 值区间
     * @return 数组
     */
    private double[] readFloat64Array(VariantSpan span) {
        int count = span.valueLength() / 8;
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = navigator.readF64(span.valueOffset() + i * 8);
        }
        return values;
    }

    /**
     * 解析定长数值型（Vector2 / Vector3 / Color 等），统一返回分量数组。
     *
     * <p>这些类型在存档里只是"几个数字挨在一起"，本工具不修改它们，
     * 但为了能完整遍历结构（例如数组里混着 Vector2），必须能读出来。
     *
     * @param span 值区间
     * @return 整数型返回 {@code long[]}，浮点型返回 {@code double[]}
     */
    private Object readFixedNumeric(VariantSpan span) {
        int type = span.type();
        if (!VariantTypes.isFixedNumeric(type)) {
            throw new IllegalStateException(
                    "暂不支持的 Variant 类型 " + VariantTypes.name(type)
                            + "（偏移 " + span.valueOffset() + "）");
        }
        int count = VariantTypes.fixedElementCount(type);
        if (VariantTypes.isIntegerBased(type)) {
            long[] values = new long[count];
            for (int i = 0; i < count; i++) {
                values[i] = navigator.readI32(span.valueOffset() + i * 4);
            }
            return values;
        }
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = navigator.readF32(span.valueOffset() + i * 4);
        }
        return values;
    }
}
