package com.chengyi.xiewa.save.format;

/**
 * 一个 Variant 值在字节流中的位置信息。
 *
 * @param type        类型码，见 {@link VariantTypes}
 * @param flags       标志位（头部第 16 位起）：低 bit 表示 64 位整/浮点，
 *                    容器则用 bit0-1、bit2-3 表示键/值的元素类型种类
 * @param valueOffset 数据区起始偏移。字符串与字节数组指向内容本身（长度字段在其前 4 字节）
 * @param endOffset   该值结束后的偏移。容器类型的此值与 {@code valueOffset} 相同，
 *                    因为容器的结束位置要遍历全部元素才能确定，请改用
 *                    {@link VariantNavigator#walkToEnd(int)}
 */
public record VariantSpan(int type, int flags, int valueOffset, int endOffset) {

    /** Variant 头部的固定长度。 */
    public static final int HEADER_SIZE = 4;

    /**
     * 是否为需要逐个元素遍历的容器。
     *
     * @return 字典或数组返回 true
     */
    public boolean isContainer() {
        return type == VariantTypes.DICTIONARY || type == VariantTypes.ARRAY;
    }

    /**
     * 是否为 64 位整数的标志位。
     *
     * @return 标志位最低位为 1 时返回 true
     */
    public boolean isWideNumeric() {
        return (flags & 1) != 0;
    }

    /**
     * 数据区长度（标量专用，容器无意义）。
     *
     * @return 字节数
     */
    public int valueLength() {
        return endOffset - valueOffset;
    }
}
