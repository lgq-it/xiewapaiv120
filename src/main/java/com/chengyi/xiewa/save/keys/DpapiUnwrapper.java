package com.chengyi.xiewa.save.keys;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.W32APIOptions;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * Windows DPAPI（{@code CryptUnprotectData}）的 JNA 封装。
 *
 * <p>游戏把存档密钥交给 DPAPI 加密后写进 {@code profile.cfg}，加密时绑定了
 * "当前 Windows 用户 + 一段自定义熵"。因此解封必须在<b>同一台机器的同一用户</b>下进行，
 * 换机器就一定失败——这也是旧版修改器要单独做一个"取密钥工具"的原因。
 * 现在这段逻辑直接内置在修改器里。
 */
public final class DpapiUnwrapper {

    /** 解密结果的最小合法长度（AES-256 密钥）。 */
    private static final int KEY_LENGTH = 32;

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    private DpapiUnwrapper() {
        throw new AssertionError("工具类不允许实例化");
    }

    /** DATA_BLOB：长度 + 数据指针。 */
    @Structure.FieldOrder({"cbData", "pbData"})
    public static class DataBlob extends Structure {
        /** 字节数。 */
        public int cbData;

        /** 数据指针。 */
        public Pointer pbData;

        /** JNA 反射构造用。 */
        public DataBlob() {
            super();
        }

        /**
         * 用一段字节构造。
         *
         * @param data 字节
         */
        public DataBlob(byte[] data) {
            super();
            this.pbData = new com.sun.jna.Memory(Math.max(data.length, 1));
            this.pbData.write(0, data, 0, data.length);
            this.cbData = data.length;
            write();
        }

        /**
         * 拷出内容并释放系统分配的内存。
         *
         * @return 字节副本
         */
        public byte[] copyAndFree() {
            byte[] out = pbData == null ? new byte[0] : pbData.getByteArray(0, cbData);
            return out;
        }
    }

    /** crypt32.dll。 */
    interface Crypt32 extends Library {
        Crypt32 INSTANCE = Native.load("crypt32", Crypt32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean CryptUnprotectData(DataBlob dataIn, Pointer description, DataBlob entropy,
                                   Pointer reserved, Pointer prompt, int flags, DataBlob dataOut);
    }

    /** kernel32.dll，用于释放 DPAPI 分配的内存。 */
    interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer LocalFree(Pointer handle);
    }

    /**
     * 解除 DPAPI 保护。
     *
     * @param wrapped 被保护的字节（{@code profile.cfg} 里 wrapped_key 的 base64 解码结果）
     * @param entropy 32 字节附加熵
     * @return 明文（正常是 32 字节密钥）
     * @throws SaveFormatException 解封失败（换了机器/用户，或熵不匹配）
     */
    public static byte[] unprotect(byte[] wrapped, byte[] entropy) throws SaveFormatException {
        if (wrapped == null || wrapped.length == 0) {
            throw new SaveFormatException("被保护的密钥为空");
        }
        DataBlob in = new DataBlob(wrapped);
        DataBlob entropyBlob = new DataBlob(entropy);
        DataBlob out = new DataBlob();
        out.cbData = 0;
        out.pbData = null;
        out.write();

        boolean ok;
        try {
            ok = Crypt32.INSTANCE.CryptUnprotectData(in, null, entropyBlob, null, null,
                    CRYPTPROTECT_UI_FORBIDDEN, out);
        } catch (UnsatisfiedLinkError e) {
            throw new SaveFormatException("当前系统不支持 DPAPI（非 Windows？）：" + e.getMessage(), e);
        }
        if (!ok) {
            int code = Native.getLastError();
            throw new SaveFormatException(
                    "DPAPI 解封失败（错误码 %d）。常见原因：这份密钥是用另一个 Windows 用户或另一台电脑加密的。"
                            .formatted(code));
        }
        out.read();
        byte[] plain = out.pbData == null ? new byte[0] : out.pbData.getByteArray(0, out.cbData);
        if (out.pbData != null) {
            Kernel32.INSTANCE.LocalFree(out.pbData);
        }
        if (plain.length != KEY_LENGTH) {
            throw new SaveFormatException("解出的密钥长度异常：期望 %d 字节，实际 %d 字节"
                    .formatted(KEY_LENGTH, plain.length));
        }
        return plain;
    }

    /**
     * 判断当前平台是否可能支持 DPAPI。
     *
     * @return Windows 返回 true
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
