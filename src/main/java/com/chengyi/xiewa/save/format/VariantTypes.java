package com.chengyi.xiewa.save.format;

/**
 * Godot 4 Variant 的类型码。
 *
 * <p>类型码存放在每个 Variant 头部 4 字节的低 8 位；高 16 位是标志位
 * （例如整型的 64 位标志、容器的元素类型标志）。这里只列出存档里实际会遇到的类型。
 */
public final class VariantTypes {

    /** 空值。 */
    public static final int NIL = 0;
    /** 布尔，占 4 字节（只用第一个字节）。 */
    public static final int BOOL = 1;
    /** 整型，32 位或 64 位由标志位低 bit 决定。 */
    public static final int INT = 2;
    /** 浮点，32 位或 64 位由标志位低 bit 决定。 */
    public static final int FLOAT = 3;
    /** 字符串，长度前缀 + 内容 + 4 字节对齐补零。 */
    public static final int STRING = 4;
    /** 字符串（内部名），编码与 {@link #STRING} 相同。 */
    public static final int STRING_NAME = 21;

    /** 字典。 */
    public static final int DICTIONARY = 27;
    /** 数组。 */
    public static final int ARRAY = 28;

    /** 字节数组：长度前缀 + 内容 + 4 字节对齐补零，存档签名就存在这里。 */
    public static final int PACKED_BYTE_ARRAY = 29;
    /** int32 数组。 */
    public static final int PACKED_INT32_ARRAY = 30;
    /** int64 数组。 */
    public static final int PACKED_INT64_ARRAY = 31;
    /** float32 数组。 */
    public static final int PACKED_FLOAT32_ARRAY = 32;
    /** float64 数组。 */
    public static final int PACKED_FLOAT64_ARRAY = 33;
    /** 字符串数组。 */
    public static final int PACKED_STRING_ARRAY = 34;

    /**
     * 定长数值型（Vector2 ~ Color，类型码 5~20）的元素个数。
     *
     * <p>下标即类型码；未定义的位置填 0，表示不是定长数值型。
     */
    private static final int[] FIXED_ELEMENT_COUNT = {
            0, 0, 0, 0, 0,          // 0~4 不是定长数值型
            2, 2, 4, 4, 3, 3,       // 5 Vector2, 6 Vector2i, 7 Rect2, 8 Rect2i, 9 Vector3, 10 Vector3i
            6, 4, 4, 4, 4,          // 11 Transform2D, 12 Vector4, 13 Vector4i, 14 Plane, 15 Quaternion
            6, 9, 12, 16, 4,        // 16 Aabb, 17 Basis, 18 Transform3D, 19 Projection, 20 Color
    };

    /**
     * 定长数值型中元素为整数的类型码（其余为浮点）。
     */
    private static final boolean[] INTEGER_BASED = {
            false, false, false, false, false,
            false, true, false, true, false, true,
            false, false, true, false, false,
            false, false, false, false, false,
    };

    private static final int MAX_FIXED_TYPE = 20;

    private VariantTypes() {
        throw new AssertionError("常量类不允许实例化");
    }

    /**
     * 判断是否为定长数值型（Vector2 / Vector3 / Color 等）。
     *
     * @param type 类型码
     * @return 是则返回 true
     */
    public static boolean isFixedNumeric(int type) {
        return type >= 5 && type <= MAX_FIXED_TYPE;
    }

    /**
     * 取定长数值型的元素个数。
     *
     * @param type 类型码
     * @return 元素个数；非定长数值型返回 0
     */
    public static int fixedElementCount(int type) {
        return isFixedNumeric(type) ? FIXED_ELEMENT_COUNT[type] : 0;
    }

    /**
     * 判断定长数值型的元素是否为整数。
     *
     * @param type 类型码
     * @return 整数型返回 true，浮点型返回 false
     */
    public static boolean isIntegerBased(int type) {
        return isFixedNumeric(type) && INTEGER_BASED[type];
    }

    /**
     * 类型码转可读名称，仅用于日志与报错信息。
     *
     * @param type 类型码
     * @return 名称
     */
    public static String name(int type) {
        return switch (type) {
            case NIL -> "Nil";
            case BOOL -> "Bool";
            case INT -> "Int";
            case FLOAT -> "Float";
            case STRING -> "String";
            case STRING_NAME -> "StringName";
            case DICTIONARY -> "Dictionary";
            case ARRAY -> "Array";
            case PACKED_BYTE_ARRAY -> "PackedByteArray";
            case PACKED_INT32_ARRAY -> "PackedInt32Array";
            case PACKED_INT64_ARRAY -> "PackedInt64Array";
            case PACKED_FLOAT32_ARRAY -> "PackedFloat32Array";
            case PACKED_FLOAT64_ARRAY -> "PackedFloat64Array";
            case PACKED_STRING_ARRAY -> "PackedStringArray";
            default -> isFixedNumeric(type) ? "VectorType(" + type + ")" : "Unknown(" + type + ")";
        };
    }
}
