package com.chengyi.xiewa.save.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * Variant 字节流的导航器：只负责「偏移计算」与「原地改写」，不做值解析。
 *
 * <p>之所以要单独做一层，是因为存档带 HMAC 签名：改写时<b>不能</b>把整个结构读出来
 * 再重新序列化，那样字节布局会变，签名必然对不上。正确做法是先在原始字节里
 * 定位到目标数值所在的 4/8 字节，原地替换，其余字节一个都不动。
 *
 * <p>本类的所有偏移都是相对传入的 {@code buffer} 的绝对偏移，语义与 Godot 的
 * {@code encode_variant} 一致：头部 4 字节（类型码 + 标志位），数据紧跟其后。
 */
public final class VariantNavigator {

    /** 递归深度上限，防止损坏数据导致无限递归。 */
    private static final int MAX_DEPTH = 64;

    private static final int CONTAINER_TYPE_NONE = 0;
    private static final int CONTAINER_TYPE_BUILTIN = 1;
    private static final int CONTAINER_TYPE_CLASS_NAME = 2;
    private static final int CONTAINER_TYPE_SCRIPT = 3;

    private final byte[] buffer;

    /**
     * @param buffer 待操作的字节数组（会被原地修改，调用方需自行决定何时复制）
     */
    public VariantNavigator(byte[] buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    /**
     * 字典中的一个条目。
     *
     * @param key                键的解析结果
     * @param keyOffset          键的 Variant 起始偏移
     * @param valueOffset        值的 Variant 起始偏移（下钻时用它继续）
     * @param valueDataOffset    值的数据区偏移
     * @param valueEndOffset     值结束后的偏移
     */
    public record DictEntry(Object key, int keyOffset, int valueOffset,
                            int valueDataOffset, int valueEndOffset) {
    }

    /**
     * 数组中的一个元素。
     *
     * @param elementOffset  元素的 Variant 起始偏移
     * @param valueDataOffset 元素的数据区偏移
     * @param valueEndOffset  元素结束后的偏移
     */
    public record ArrayEntry(int elementOffset, int valueDataOffset, int valueEndOffset) {
    }

    // ---------------------------------------------------------------- 基础读取

    /**
     * 读取 4 字节无符号整数（实际按有符号返回，调用方按需用掩码处理）。
     *
     * @param offset 偏移
     * @return 整数值
     */
    public int readU32(int offset) {
        require(offset, 4);
        return (buffer[offset] & 0xFF)
                | (buffer[offset + 1] & 0xFF) << 8
                | (buffer[offset + 2] & 0xFF) << 16
                | (buffer[offset + 3] & 0xFF) << 24;
    }

    /**
     * 读取 4 字节有符号整数。
     *
     * @param offset 偏移
     * @return 整数值
     */
    public int readI32(int offset) {
        return readU32(offset);
    }

    /**
     * 读取 8 字节有符号整数。
     *
     * @param offset 偏移
     * @return 整数值
     */
    public long readI64(int offset) {
        require(offset, 8);
        long low = readU32(offset) & 0xFFFFFFFFL;
        long high = readU32(offset + 4) & 0xFFFFFFFFL;
        return low | (high << 32);
    }

    /**
     * 读取 4 字节单精度浮点。
     *
     * @param offset 偏移
     * @return 浮点值
     */
    public float readF32(int offset) {
        return Float.intBitsToFloat(readU32(offset));
    }

    /**
     * 读取 8 字节双精度浮点。
     *
     * @param offset 偏移
     * @return 浮点值
     */
    public double readF64(int offset) {
        return Double.longBitsToDouble(readI64(offset));
    }

    /**
     * 读取一个字节（无符号）。
     *
     * @param offset 偏移
     * @return 0~255
     */
    public int readU8(int offset) {
        require(offset, 1);
        return buffer[offset] & 0xFF;
    }

    /**
     * 读取字符串内容（长度字段在 {@code dataOffset - 4}）。
     *
     * @param dataOffset 内容起始偏移
     * @return UTF-8 字符串
     */
    public String readString(int dataOffset) {
        int length = readU32(dataOffset - 4);
        if (length < 0) {
            throw new IllegalStateException("字符串长度为负：" + length);
        }
        require(dataOffset, length);
        return new String(buffer, dataOffset, length, StandardCharsets.UTF_8);
    }

    /**
     * 拷贝一段字节。
     *
     * @param offset 起始偏移
     * @param length 长度
     * @return 副本
     */
    public byte[] copyBytes(int offset, int length) {
        require(offset, length);
        byte[] out = new byte[length];
        System.arraycopy(buffer, offset, out, 0, length);
        return out;
    }

    /**
     * 暴露底层数组，供需要整体读值的场景使用。
     *
     * @return 底层数组（非副本）
     */
    public byte[] buffer() {
        return buffer;
    }

    /**
     * 底层数组长度。
     *
     * @return 字节数
     */
    public int size() {
        return buffer.length;
    }

    // ---------------------------------------------------------------- 区间计算

    /**
     * 计算某个偏移处 Variant 的区间信息。
     *
     * <p>注意：容器类型返回的 {@code endOffset} 等于 {@code valueOffset}，
     * 因为结束位置必须先遍历元素；需要结束位置请用 {@link #walkToEnd(int)}。
     *
     * @param offset Variant 起始偏移
     * @return 区间信息
     */
    public VariantSpan spanAt(int offset) {
        int header = readU32(offset);
        int type = header & 0xFF;
        int flags = header >>> 16;
        int value = offset + VariantSpan.HEADER_SIZE;

        if (type == VariantTypes.NIL) {
            return new VariantSpan(type, flags, value, value);
        }
        if (type == VariantTypes.BOOL) {
            return new VariantSpan(type, flags, value, value + 4);
        }
        if (type == VariantTypes.INT || type == VariantTypes.FLOAT) {
            return new VariantSpan(type, flags, value, value + ((flags & 1) != 0 ? 8 : 4));
        }
        if (type == VariantTypes.STRING || type == VariantTypes.STRING_NAME) {
            int length = readU32(value);
            return new VariantSpan(type, flags, value + 4, value + 4 + align4(length));
        }
        if (type == VariantTypes.PACKED_BYTE_ARRAY) {
            int length = readU32(value);
            return new VariantSpan(type, flags, value + 4, value + 4 + align4(length));
        }
        if (type == VariantTypes.PACKED_INT32_ARRAY || type == VariantTypes.PACKED_FLOAT32_ARRAY) {
            int count = readU32(value);
            return new VariantSpan(type, flags, value + 4, value + 4 + count * 4);
        }
        if (type == VariantTypes.PACKED_INT64_ARRAY || type == VariantTypes.PACKED_FLOAT64_ARRAY) {
            int count = readU32(value);
            return new VariantSpan(type, flags, value + 4, value + 4 + count * 8);
        }
        if (type == VariantTypes.DICTIONARY) {
            int keyKind = (header >>> 16) & 3;
            int valueKind = (header >>> 18) & 3;
            int cursor = skipContainerType(keyKind, value);
            cursor = skipContainerType(valueKind, cursor);
            int dataStart = cursor + 4; // 越过条目数，条目紧随其后
            return new VariantSpan(type, flags, dataStart, dataStart);
        }
        if (type == VariantTypes.ARRAY) {
            int kind = (header >>> 16) & 3;
            int dataStart = skipContainerType(kind, value) + 4; // 越过元素个数
            return new VariantSpan(type, flags, dataStart, dataStart);
        }
        if (VariantTypes.isFixedNumeric(type)) {
            int elements = VariantTypes.fixedElementCount(type);
            // Godot 中整数型（Vector2i 等）与浮点型的分量均占 4 字节
            final int componentSize = 4;
            return new VariantSpan(type, flags, value, value + elements * componentSize);
        }
        throw new IllegalStateException(
                "暂不支持的 Variant 类型 %s（偏移 %d）".formatted(VariantTypes.name(type), offset));
    }

    /**
     * 跳过容器头部里紧跟的「元素类型」描述。
     *
     * <p>编码规则：0 无、1 内建类型（4 字节）、2 类名（字符串）、3 脚本（嵌套 Variant）。
     *
     * @param kind   种类
     * @param offset 起始偏移
     * @return 跳过后的偏移
     */
    public int skipContainerType(int kind, int offset) {
        return switch (kind) {
            case CONTAINER_TYPE_NONE -> offset;
            case CONTAINER_TYPE_BUILTIN -> offset + 4;
            case CONTAINER_TYPE_CLASS_NAME -> {
                int length = readU32(offset);
                yield offset + 4 + align4(length);
            }
            case CONTAINER_TYPE_SCRIPT -> walkToEnd(offset);
            default -> throw new IllegalStateException("未知的容器元素类型种类：" + kind);
        };
    }

    /**
     * 从某个值出发，算出它结束后的偏移（会递归跳过所有嵌套结构）。
     *
     * @param offset Variant 起始偏移
     * @return 结束偏移
     */
    public int walkToEnd(int offset) {
        return walkToEnd(offset, 0);
    }

    private int walkToEnd(int offset, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Variant 嵌套超过 " + MAX_DEPTH + " 层，数据可能已损坏");
        }
        int header = readU32(offset);
        int type = header & 0xFF;
        int value = offset + VariantSpan.HEADER_SIZE;

        if (type == VariantTypes.DICTIONARY) {
            int keyKind = (header >>> 16) & 3;
            int valueKind = (header >>> 18) & 3;
            int cursor = skipContainerType(keyKind, value);
            cursor = skipContainerType(valueKind, cursor);
            int count = readU32(cursor) & 0x7FFFFFFF;
            cursor += 4;
            int end = cursor;
            for (int i = 0; i < count; i++) {
                cursor = walkToEnd(cursor, depth + 1);
                cursor = walkToEnd(cursor, depth + 1);
                end = cursor;
            }
            return end;
        }
        if (type == VariantTypes.ARRAY) {
            int kind = (header >>> 16) & 3;
            int cursor = skipContainerType(kind, value);
            int count = readU32(cursor) & 0x7FFFFFFF;
            cursor += 4;
            int end = cursor;
            for (int i = 0; i < count; i++) {
                cursor = walkToEnd(cursor, depth + 1);
                end = cursor;
            }
            return end;
        }
        return spanAt(offset).endOffset();
    }

    /**
     * 列出字典的全部条目（按写入顺序）。
     *
     * @param dictOffset 字典的 Variant 起始偏移
     * @return 条目列表
     */
    public List<DictEntry> dictEntries(int dictOffset) {
        int header = readU32(dictOffset);
        if ((header & 0xFF) != VariantTypes.DICTIONARY) {
            throw new IllegalStateException(
                    "偏移 %d 处不是字典，而是 %s".formatted(dictOffset, VariantTypes.name(header & 0xFF)));
        }
        int value = dictOffset + VariantSpan.HEADER_SIZE;
        int keyKind = (header >>> 16) & 3;
        int valueKind = (header >>> 18) & 3;
        int cursor = skipContainerType(keyKind, value);
        cursor = skipContainerType(valueKind, cursor);
        int count = readU32(cursor) & 0x7FFFFFFF;
        cursor += 4;

        List<DictEntry> entries = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            int keyOffset = cursor;
            int valueOffset = walkToEnd(keyOffset);
            int valueEnd = walkToEnd(valueOffset);
            Object key = readKey(keyOffset);
            VariantSpan span = spanAt(valueOffset);
            entries.add(new DictEntry(key, keyOffset, valueOffset, span.valueOffset(), valueEnd));
            cursor = valueEnd;
        }
        return entries;
    }

    /**
     * 列出数组的全部元素（按顺序）。
     *
     * @param arrayOffset 数组的 Variant 起始偏移
     * @return 元素列表
     */
    public List<ArrayEntry> arrayEntries(int arrayOffset) {
        int header = readU32(arrayOffset);
        if ((header & 0xFF) != VariantTypes.ARRAY) {
            throw new IllegalStateException(
                    "偏移 %d 处不是数组，而是 %s".formatted(arrayOffset, VariantTypes.name(header & 0xFF)));
        }
        int value = arrayOffset + VariantSpan.HEADER_SIZE;
        int kind = (header >>> 16) & 3;
        int cursor = skipContainerType(kind, value);
        int count = readU32(cursor) & 0x7FFFFFFF;
        cursor += 4;

        List<ArrayEntry> entries = new ArrayList<>(Math.min(count, 4096));
        for (int i = 0; i < count; i++) {
            int elementOffset = cursor;
            VariantSpan span = spanAt(elementOffset);
            int valueEnd = walkToEnd(elementOffset);
            entries.add(new ArrayEntry(elementOffset, span.valueOffset(), valueEnd));
            cursor = valueEnd;
        }
        return entries;
    }

    // ---------------------------------------------------------------- 路径下钻

    /**
     * 按路径下钻，返回目标值的 Variant 起始偏移。
     *
     * <p>路径元素为 {@link String} 时按字典键匹配，为 {@link Integer} 时按数组下标。
     *
     * @param startOffset 起点（某层容器的 Variant 偏移）
     * @param path        路径
     * @return 目标值的 Variant 起始偏移
     * @throws SaveFormatException 键不存在、下标越界，或中途遇到非容器
     */
    public int locate(int startOffset, List<?> path) throws SaveFormatException {
        int cursor = startOffset;
        for (int depth = 0; depth < path.size(); depth++) {
            Object step = path.get(depth);
            VariantSpan span = spanAt(cursor);

            if (span.type() == VariantTypes.DICTIONARY) {
                boolean found = false;
                for (DictEntry entry : dictEntries(cursor)) {
                    if (matches(entry.key(), step)) {
                        cursor = entry.valueOffset();
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new SaveFormatException(
                            "路径 %s 中找不到键 %s".formatted(prefix(path, depth + 1), step));
                }
            } else if (span.type() == VariantTypes.ARRAY) {
                int index = asIndex(step, path, depth);
                List<ArrayEntry> entries = arrayEntries(cursor);
                if (index < 0 || index >= entries.size()) {
                    throw new SaveFormatException(
                            "路径 %s 下标 %d 越界（数组长度 %d）"
                                    .formatted(prefix(path, depth + 1), index, entries.size()));
                }
                cursor = entries.get(index).elementOffset();
            } else {
                throw new SaveFormatException(
                        "路径 %s 无法继续下钻：当前是 %s，不是容器"
                                .formatted(prefix(path, depth + 1), VariantTypes.name(span.type())));
            }
        }
        return cursor;
    }

    /**
     * 原地改写一个布尔值（占用与整数相同，都是定长 4 字节，可以安全翻转）。
     *
     * @param startOffset 起点（承载该值的容器的 Variant 偏移）
     * @param path        路径
     * @param value       新值
     * @throws SaveFormatException 路径无效，或目标不是布尔
     */
    public void patchBool(int startOffset, List<?> path, boolean value) throws SaveFormatException {
        int target = locate(startOffset, path);
        VariantSpan span = spanAt(target);
        if (span.type() != VariantTypes.BOOL) {
            throw new SaveFormatException(
                    "路径 %s 的目标是 %s，不是布尔".formatted(path, VariantTypes.name(span.type())));
        }
        writeU32(target + VariantSpan.HEADER_SIZE, value ? 1 : 0);
    }

    /**
     * 原地改写一个整数值。
     *
     * @param startOffset 起点（承载该值的容器的 Variant 偏移）
     * @param path        路径
     * @param value       新值
     * @throws SaveFormatException 路径无效，或目标不是整数
     */
    public void patchInt(int startOffset, List<?> path, long value) throws SaveFormatException {
        int target = locate(startOffset, path);
        VariantSpan span = spanAt(target);
        if (span.type() != VariantTypes.INT) {
            throw new SaveFormatException(
                    "路径 %s 的目标是 %s，不是整数".formatted(path, VariantTypes.name(span.type())));
        }
        int dataOffset = target + VariantSpan.HEADER_SIZE;
        if (span.isWideNumeric()) {
            writeI64(dataOffset, value);
        } else {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new SaveFormatException(
                        "值 %d 超出 int32 范围，该字段是 32 位整数".formatted(value));
            }
            writeU32(dataOffset, (int) value);
        }
    }

    /**
     * 原地改写一个字符串值。
     *
     * <p>要求新旧字符串在磁盘上的字节占用完全相同（含 4 字节对齐补零），
     * 因为长度字段不能变，否则整个后续偏移全部错位。
     *
     * @param startOffset 起点
     * @param path        路径
     * @param newValue    新字符串
     * @throws SaveFormatException 路径无效、目标不是字符串，或字节占用不一致
     */
    public void patchString(int startOffset, List<?> path, String newValue) throws SaveFormatException {
        int target = locate(startOffset, path);
        VariantSpan span = spanAt(target);
        if (span.type() != VariantTypes.STRING && span.type() != VariantTypes.STRING_NAME) {
            throw new SaveFormatException(
                    "路径 %s 的目标是 %s，不是字符串".formatted(path, VariantTypes.name(span.type())));
        }
        byte[] encoded = newValue.getBytes(StandardCharsets.UTF_8);
        int oldLength = span.endOffset() - span.valueOffset();
        int newLength = align4(encoded.length);
        if (newLength != oldLength) {
            throw new SaveFormatException(
                    "字符串「%s」的字节占用（%d）与原值（%d）不同，无法原地替换"
                            .formatted(newValue, newLength, oldLength));
        }
        System.arraycopy(encoded, 0, buffer, span.valueOffset(), encoded.length);
        for (int i = span.valueOffset() + encoded.length; i < span.valueOffset() + newLength; i++) {
            buffer[i] = 0;
        }
    }

    /**
     * 改写一段原始字节（用于签名等定长字段）。
     *
     * @param offset 偏移
     * @param data   新内容
     * @param length 要覆盖的长度，通常与 {@code data.length} 相同
     */
    public void writeBytes(int offset, byte[] data, int length) {
        require(offset, length);
        if (data.length < length) {
            throw new IllegalArgumentException(
                    "待写入数据长度 %d 小于目标长度 %d".formatted(data.length, length));
        }
        System.arraycopy(data, 0, buffer, offset, length);
    }

    /**
     * 读写一个 32 位整数。
     *
     * @param offset 偏移
     * @param value  值
     */
    public void writeU32(int offset, int value) {
        require(offset, 4);
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
        buffer[offset + 2] = (byte) (value >>> 16);
        buffer[offset + 3] = (byte) (value >>> 24);
    }

    /**
     * 读写一个 64 位整数。
     *
     * @param offset 偏移
     * @param value  值
     */
    public void writeI64(int offset, long value) {
        writeU32(offset, (int) value);
        writeU32(offset + 4, (int) (value >>> 32));
    }

    // ---------------------------------------------------------------- 内部工具

    /**
     * 读取字典键的解析结果（只支持字符串键，存档里也只出现字符串键）。
     */
    private Object readKey(int keyOffset) {
        VariantSpan span = spanAt(keyOffset);
        if (span.type() == VariantTypes.STRING || span.type() == VariantTypes.STRING_NAME) {
            return readString(span.valueOffset());
        }
        return "<" + VariantTypes.name(span.type()) + "@" + keyOffset + ">";
    }

    private static boolean matches(Object key, Object step) {
        if (step == null) {
            return key == null;
        }
        return key.toString().equals(step.toString());
    }

    private static int asIndex(Object step, List<?> path, int depth) throws SaveFormatException {
        if (step instanceof Integer index) {
            return index;
        }
        if (step instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new SaveFormatException(
                        "路径 %s 中 %s 不是合法下标".formatted(prefix(path, depth + 1), text), e);
            }
        }
        throw new SaveFormatException(
                "路径 %s 中 %s 不能作为数组下标".formatted(prefix(path, depth + 1), step));
    }

    private static List<Object> prefix(List<?> path, int end) {
        return new ArrayList<>(path.subList(0, Math.min(end, path.size())));
    }

    private static int align4(int value) {
        return value + ((4 - value % 4) % 4);
    }

    private void require(int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > buffer.length) {
            throw new IllegalStateException(
                    "读取越界：偏移 %d 长度 %d，缓冲区总长 %d"
                            .formatted(offset, length, buffer.length));
        }
    }
}
