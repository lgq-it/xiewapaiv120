package com.chengyi.xiewa.save.model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import com.chengyi.xiewa.save.core.GuardMac;
import com.chengyi.xiewa.save.core.SaveFormatException;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.ptr.PointerByReference;

/**
 * 离线重签「存档水位」，把账本快照代数归零（snap_g = 0），让游戏走迁移流程。
 *
 * <p>写档前先由 {@code SaveDocument.stripLedgerSnap()} 去掉 header 里的
 * {@code ledger_snap}；这里再配合把水位镜像里的 {@code snap_g} 改成 0，
 * 游戏读档时就会判定为「旧档首次建快照」，进游戏后由游戏自己重新封存一份
 * 与新数据一致的账本快照，从而“承认”离线修改。
 *
 * <p>字段用「字符串键定位 + 原地补丁」实现，不完整遍历 Variant，避免撞上
 * VariantNavigator 暂不支持的记录类型。
 */
public final class WatermarkForge {

    private static final byte[] ENTROPY_A = hex(
            "2c740f131381cbfbafbe8306c6ca29353d209d97269deff43b08d6716543b325");
    private static final byte[] ENTROPY_B = hex(
            "47b1babf88713460c654da0af8bd1a484b435641bf8b99278625b4acd20998bc");

    private WatermarkForge() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 重签水位记录并写回 LOCAL / PROGRAMDATA 两处镜像。
     *
     * @param savePath save.dat 的路径
     * @param newHash  即将写入的存档文件的 SHA-256（64 位十六进制）
     */
    public static void resetSnapshot(Path savePath, String newHash) throws Exception {
        Path profileDir = savePath.toAbsolutePath().getParent();
        if (profileDir == null) {
            throw new IOException("无法确定存档所在目录：" + savePath);
        }
        String profileId = profileDir.getFileName().toString();
        String name = sha1(profileId.getBytes(StandardCharsets.UTF_8)).substring(0, 16);

        Path localFile = Path.of(envOrEmpty("LOCALAPPDATA"), "Ombrel", "cache", name + ".idx");
        byte[] blob = Files.readAllBytes(localFile);
        if (blob.length < 8 || !"XWD3".equals(new String(blob, 0, 4, StandardCharsets.US_ASCII))) {
            throw new SaveFormatException("水位文件格式异常：" + localFile);
        }
        int blobLen = readU32(blob, 4);
        byte[] plain = Crypt32.INSTANCE.unprotect(copy(blob, 8, blobLen), ENTROPY_A);
        if (plain == null) {
            throw new SaveFormatException("水位 DPAPI 解密失败");
        }

        int bodyData = -1;
        int bodyLen = -1;
        int macData = -1;
        int cursor = 8; // 跳过信封字典的类型(4)与条目数(4)
        int envCount = readU32(plain, 4) & 0x7FFFFFFF;
        for (int i = 0; i < envCount; i++) {
            int keyType = readU32(plain, cursor) & 0xFF;
            int keyLen = readU32(plain, cursor + 4);
            String key = new String(plain, cursor + 8, keyLen, StandardCharsets.UTF_8);
            cursor = align4(cursor + 8 + keyLen);
            int valueType = readU32(plain, cursor) & 0xFF;
            if (valueType == 29) {
                int len = readU32(plain, cursor + 4);
                if ("b".equals(key)) {
                    bodyData = cursor + 8;
                    bodyLen = len;
                } else if ("m".equals(key)) {
                    macData = cursor + 8;
                }
                cursor = align4(cursor + 8 + len);
            } else if (valueType == 2) {
                boolean wide = ((readU32(plain, cursor) >>> 16) & 1) != 0;
                cursor = cursor + 4 + (wide ? 8 : 4);
            } else {
                throw new SaveFormatException("水位信封字段类型异常：" + valueType);
            }
        }
        if (bodyData < 0 || macData < 0 || bodyLen < 0) {
            throw new SaveFormatException("水位信封缺少 b/m 字段");
        }

        byte[] body = copy(plain, bodyData, bodyLen);
        patchString(body, "hash_cur", newHash);
        patchString(body, "v4_hash", newHash);
        patchInt32(body, "snap_g", 0);
        patchBool(body, "sealed", false);
        patchInt32(body, "record_revision", 2_000_000_000);

        long committed = readInt(body, "committed");
        String hashPrev = readString(body, "hash_prev");
        String tag5Msg = "%s|%d|%s".formatted(newHash, committed, hashPrev);
        byte[] tag5 = GuardMac.sign("tag5", profileId.getBytes(StandardCharsets.UTF_8),
                tag5Msg.getBytes(StandardCharsets.UTF_8));
        patchBytes(body, "t5", tag5);

        byte[] mark = GuardMac.sign("mark", new byte[0], body);
        System.arraycopy(body, 0, plain, bodyData, body.length);
        System.arraycopy(mark, 0, plain, macData, 32);

        byte[] sealedA = Crypt32.INSTANCE.protect(plain, ENTROPY_A);
        byte[] sealedB = Crypt32.INSTANCE.protect(plain, ENTROPY_B);
        if (sealedA == null || sealedB == null || sealedA.length != blobLen || sealedB.length != blobLen) {
            throw new SaveFormatException("水位 DPAPI 重加密失败");
        }
        byte[] out = new byte[8 + sealedA.length + sealedB.length];
        System.arraycopy("XWD3".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        writeU32(out, 4, sealedA.length);
        System.arraycopy(sealedA, 0, out, 8, sealedA.length);
        System.arraycopy(sealedB, 0, out, 8 + sealedA.length, sealedB.length);

        for (String base : new String[]{
                envOrEmpty("LOCALAPPDATA") + "\\Ombrel\\cache",
                envOrEmpty("PROGRAMDATA") + "\\Ombrel\\cache"}) {
            Path dir = Path.of(base);
            Files.createDirectories(dir);
            Files.write(dir.resolve(name + ".idx"), out);
        }
        Advapi32.INSTANCE.writeCacheValue(name, out);
    }

    private static int findValueOffset(byte[] b, String key) {
        int idx = lastIndexOf(b, key.getBytes(StandardCharsets.UTF_8));
        if (idx < 0) {
            throw new IllegalStateException("找不到字段：" + key);
        }
        return align4(idx + key.length());
    }

    private static void patchString(byte[] body, String key, String value) {
        int valueOff = findValueOffset(body, key);
        int contentOff = valueOff + 8;
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length != readU32(body, valueOff + 4)) {
            throw new IllegalStateException("字符串长度不一致：" + key);
        }
        System.arraycopy(data, 0, body, contentOff, data.length);
    }

    private static void patchInt32(byte[] body, String key, int value) {
        int valueOff = findValueOffset(body, key);
        writeU32(body, valueOff + 4, value);
    }

    private static void patchBool(byte[] body, String key, boolean value) {
        int valueOff = findValueOffset(body, key);
        writeU32(body, valueOff + 4, value ? 1 : 0);
    }

    private static void patchBytes(byte[] body, String key, byte[] value) {
        int valueOff = findValueOffset(body, key);
        System.arraycopy(value, 0, body, valueOff + 8, value.length);
    }

    private static long readInt(byte[] body, String key) {
        int valueOff = findValueOffset(body, key);
        int flags = readU32(body, valueOff) >>> 16;
        if ((flags & 1) != 0) {
            return ByteBuffer.wrap(body, valueOff + 4, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        }
        return readU32(body, valueOff + 4);
    }

    private static String readString(byte[] body, String key) {
        int valueOff = findValueOffset(body, key);
        int length = readU32(body, valueOff + 4);
        return new String(body, valueOff + 8, length, StandardCharsets.UTF_8);
    }

    private static int lastIndexOf(byte[] b, byte[] needle) {
        outer:
        for (int i = b.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (b[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    private static int readU32(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void writeU32(byte[] b, int off, int value) {
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    private static byte[] copy(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    private static String sha1(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String envOrEmpty(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    @Structure.FieldOrder({"cbData", "pbData"})
    public static class DataBlob extends Structure {
        public int cbData;
        public Pointer pbData;

        public DataBlob() {
            super();
        }

        public DataBlob(byte[] data) {
            super();
            this.pbData = new Memory(Math.max(data.length, 1));
            this.pbData.write(0, data, 0, data.length);
            this.cbData = data.length;
            write();
        }

        public byte[] copyAndFree() {
            return pbData == null ? new byte[0] : pbData.getByteArray(0, cbData);
        }
    }

    interface Crypt32 extends Library {
        Crypt32 INSTANCE = Native.load("crypt32", Crypt32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean CryptUnprotectData(DataBlob dataIn, Pointer description, DataBlob entropy,
                                   Pointer reserved, Pointer prompt, int flags, DataBlob dataOut);

        boolean CryptProtectData(DataBlob dataIn, String description, DataBlob entropy,
                                 Pointer reserved, Pointer prompt, int flags, DataBlob dataOut);

        default byte[] unprotect(byte[] data, byte[] entropy) {
            DataBlob in = new DataBlob(data);
            DataBlob ent = new DataBlob(entropy);
            DataBlob out = new DataBlob();
            if (!CryptUnprotectData(in, null, ent, null, null, 0, out)) {
                return null;
            }
            return out.copyAndFree();
        }

        default byte[] protect(byte[] data, byte[] entropy) {
            DataBlob in = new DataBlob(data);
            DataBlob ent = new DataBlob(entropy);
            DataBlob out = new DataBlob();
            if (!CryptProtectData(in, "ombrel", ent, null, null, 0x1, out)) {
                return null;
            }
            return out.copyAndFree();
        }
    }

    interface Advapi32 extends Library {
        Advapi32 INSTANCE = Native.load("advapi32", Advapi32.class, W32APIOptions.DEFAULT_OPTIONS);

        int RegOpenKeyExW(Pointer root, String subKey, int options, int samDesired,
                          PointerByReference out);

        int RegSetValueExW(Pointer key, String valueName, int reserved, int type,
                           byte[] data, int size);

        int RegCloseKey(Pointer key);

        default void writeCacheValue(String name, byte[] data) throws SaveFormatException {
            long hkcu = 0x80000001L;
            int keySetValue = 0x0002;
            int regBinary = 3;
            PointerByReference phKey = new PointerByReference();
            int openRc = RegOpenKeyExW(Pointer.createConstant(hkcu), "Software\\Ombrel\\Cache",
                    0, keySetValue, phKey);
            if (openRc != 0) {
                throw new SaveFormatException("无法打开注册表 Cache 键");
            }
            int rc = RegSetValueExW(phKey.getValue(), name, 0, regBinary, data, data.length);
            RegCloseKey(phKey.getValue());
            if (rc != 0) {
                throw new SaveFormatException("写入注册表失败（错误码 " + rc + "）");
            }
        }
    }
}
