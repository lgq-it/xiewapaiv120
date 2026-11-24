package com.chengyi.xiewa.save.model;

import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

/**
 * 机器级封禁记录 XWDL3 的清理。
 *
 * <p>1.1.2 的 {@code 数值守卫.修改器开档检查} 一旦命中 {@code machine_seal_all()}
 * 就会把封印类型写成 {@code x8}。这条记录是机器级的，跟存档、水位都无关：
 * 只清存档或只重签水位都不会解除它，必须把这三处镜像一起删掉：
 * <ul>
 *   <li>注册表 {@code HKCU\Software\Ombrel\Cache\c1d15cd07ae0b590}</li>
 *   <li>{@code %LOCALAPPDATA%\Ombrel\cache\c1d15cd07ae0b590.idx}</li>
 *   <li>{@code %PROGRAMDATA%\Ombrel\cache\c1d15cd07ae0b590.idx}</li>
 * </ul>
 */
public final class MachineSeal {

    /** 机器封禁记录在 Cache 键里的固定值名。 */
    private static final String VALUE_NAME = "c1d15cd07ae0b590";

    private MachineSeal() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 清掉机器封禁记录，任何一步失败都静默继续，不打断主流程。
     */
    public static void clear() {
        Advapi32.INSTANCE.deleteCacheValue(VALUE_NAME);
        for (String base : new String[]{
                envOrEmpty("LOCALAPPDATA") + "\\Ombrel\\cache",
                envOrEmpty("PROGRAMDATA") + "\\Ombrel\\cache"}) {
            try {
                Files.deleteIfExists(Path.of(base, VALUE_NAME + ".idx"));
            } catch (Exception ignored) {
                // 单个文件占用删不掉不影响整体
            }
        }
        // 1.2.0 起的延迟封禁会在 user://cache/dfn.json 留一份诊断记录；
        // 一并清掉，避免下一次开机按它把磁盘痕迹判到期。
        try {
            Files.deleteIfExists(Path.of(
                    envOrEmpty("APPDATA"), "Ombrel", "rt", "cache", "dfn.json"));
        } catch (Exception ignored) {
            // 删不掉不阻塞主流程
        }
    }

    private static String envOrEmpty(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    interface Advapi32 extends Library {
        Advapi32 INSTANCE = Native.load("advapi32", Advapi32.class, W32APIOptions.DEFAULT_OPTIONS);

        int RegOpenKeyExW(Pointer root, String subKey, int options, int samDesired,
                          PointerByReference out);

        int RegDeleteValueW(Pointer key, String valueName);

        int RegCloseKey(Pointer key);

        default void deleteCacheValue(String valueName) {
            long hkcu = 0x80000001L;
            int keySetValue = 0x0002;
            int errorFileNotFound = 2;
            PointerByReference phKey = new PointerByReference();
            int openRc = RegOpenKeyExW(Pointer.createConstant(hkcu),
                    "Software\\Ombrel\\Cache", 0, keySetValue, phKey);
            if (openRc != 0) {
                return;
            }
            int rc = RegDeleteValueW(phKey.getValue(), valueName);
            RegCloseKey(phKey.getValue());
            if (rc != 0 && rc != errorFileNotFound) {
                // 失败也静默：清理只是保险，不应挡住保存/还原流程
            }
        }
    }
}
