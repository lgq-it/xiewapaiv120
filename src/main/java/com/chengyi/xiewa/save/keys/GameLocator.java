package com.chengyi.xiewa.save.keys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 定位游戏主程序 {@code XiewaPai.exe}。
 *
 * <p>存档密钥由 DPAPI 保护，而 DPAPI 的附加熵是从游戏 exe 里的一张常量表算出来的，
 * 所以解析密钥必须先找到 exe 本体。用户可能把修改器和游戏放在同一目录、放在子目录，
 * 也可能分在不同磁盘，这里按"越近越优先"的顺序依次尝试。
 */
public final class GameLocator {

    /** 游戏主程序文件名。 */
    private static final String GAME_EXE = "XiewaPai.exe";

    /** 游戏目录常见名称（含版本号，故按前缀匹配）。 */
    private static final String GAME_DIR_PREFIX = "XiewaPai";

    private GameLocator() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 在若干候选位置中寻找游戏主程序。
     *
     * @return 命中的 exe 路径；全部未命中返回 {@code null}
     */
    public static Path findGameExe() {
        for (Path candidate : candidates()) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /**
     * 列出全部候选路径（按优先级），便于出错时提示用户实际尝试过哪些位置。
     *
     * @return 候选路径
     */
    public static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        Path program = programDirectory();
        Path working = Paths.get("").toAbsolutePath();

        for (Path base : new Path[]{program, working}) {
            if (base == null) {
                continue;
            }
            addAncestors(base, out);
        }

        // 兜底：常见盘符下的 \功夫派\XiewaPai x.y.z\
        for (String drive : new String[]{"D:", "E:", "F:", "C:"}) {
            Path root = Paths.get(drive + "\\功夫派");
            if (Files.isDirectory(root)) {
                out.addAll(siblingGameDirs(root));
            }
        }
        return out;
    }

    /**
     * 从某个目录出发逐级向上找（最多 4 级）：每级都看一眼本级的 exe 和形如
     * {@code XiewaPai 1.0.3} 的子目录。
     *
     * <p>之所以要向上多走几级：打包后程序位于 {@code 修改器\chengyi-updatepai\app\}，
     * 而游戏可能在 {@code xieerpai\XiewaPai 1.0.3\}，中间隔着好几层。
     */
    private static void addAncestors(Path base, List<Path> out) {
        Path directory = base;
        for (int level = 0; level < 4 && directory != null; level++) {
            out.add(directory.resolve(GAME_EXE));
            out.addAll(siblingGameDirs(directory));
            directory = directory.getParent();
        }
    }

    /**
     * 程序自身所在目录：打包运行是 exe 所在文件夹，开发时是 {@code target/classes} 的上级。
     *
     * @return 目录
     */
    public static Path programDirectory() {
        try {
            Path location = Paths.get(GameLocator.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            if (dir != null) {
                // target/classes -> 项目根
                Path parent = dir.getParent();
                if (parent != null && "classes".equals(dir.getFileName().toString())
                        && "target".equals(parent.getFileName().toString())) {
                    return parent.getParent() == null ? dir : parent.getParent();
                }
                return dir;
            }
        } catch (Exception ignored) {
            // 取不到代码位置时退回工作目录
        }
        return Paths.get("").toAbsolutePath();
    }

    /**
     * 扫描某个目录下形如 {@code XiewaPai 1.0.3} 的子目录。
     */
    private static List<Path> siblingGameDirs(Path base) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (var stream = Files.list(base)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(GAME_DIR_PREFIX))
                    .sorted()
                    .forEach(p -> out.add(p.resolve(GAME_EXE)));
        } catch (Exception ignored) {
            // 目录不可读就跳过
        }
        return out;
    }
}
