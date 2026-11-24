package com.chengyi.xiewa.save.ui;

import java.util.Locale;

/**
 * 检测游戏是否正在运行。
 *
 * <p>游戏运行时会把自己内存里的存档写回磁盘，此时修改文件不是被覆盖、就是被判定为外部篡改。
 * 所以修改器必须在这种状态下自我锁定——这是"防呆"的核心。
 */
public final class GameWatcher {

    /** 游戏进程名（小写）。 */
    private static final String GAME_PROCESS = "xiewapai.exe";

    private GameWatcher() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 游戏是否在运行。
     *
     * @return 运行中返回 true；检测过程中出现异常时按"未运行"处理
     */
    public static boolean isGameRunning() {
        try {
            return ProcessHandle.allProcesses().anyMatch(process -> {
                String command = process.info().command().orElse("");
                if (command.isEmpty()) {
                    return false;
                }
                String normalized = command.toLowerCase(Locale.ROOT).replace('\\', '/');
                int slash = normalized.lastIndexOf('/');
                String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
                return GAME_PROCESS.equals(name);
            });
        } catch (RuntimeException e) {
            return false;
        }
    }
}
