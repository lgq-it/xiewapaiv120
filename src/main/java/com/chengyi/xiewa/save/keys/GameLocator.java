package com.chengyi.xiewa.save.keys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 定位游戏主程序 {@code XiewaPai.exe}。
 *
 * <p>旧格式存档密钥的 DPAPI 熵从 exe 常量表推出，所以必须找到 exe 本体；
 * 新版 {@code XWD3} 信封虽然不再依赖 exe，但定位到游戏目录仍有助于判断版本。
 * 用户可能把修改器与游戏放在同一目录、子目录或不同磁盘，这里按下列顺序尝试：
 * <ol>
 *   <li>正在运行的游戏进程（最准）；</li>
 *   <li>手动配置的 {@code gamepath.txt} 或环境变量 {@code XIEWAPAI_EXE}；</li>
 *   <li>程序目录/工作目录逐级向上，以及各层的 {@code XiewaPai*} 子目录；</li>
 *   <li>各盘符下常见的 {@code 功夫派}、{@code 游戏}、{@code Games} 目录（限深度递归）。</li>
 * </ol>
 */
public final class GameLocator {

    /** 游戏主程序文件名。 */
    private static final String GAME_EXE = "XiewaPai.exe";

    /** 游戏目录常见名称（含版本号，故按前缀匹配）。 */
    private static final String GAME_DIR_PREFIX = "XiewaPai";

    /** 从程序/工作目录向上查找的层数。 */
    private static final int MAX_ANCESTOR_LEVELS = 6;

    /** 常见游戏根目录的名称。 */
    private static final String[] GAME_ROOT_NAMES = {"功夫派", "游戏", "Games", "games"};

    /** 手动指定游戏路径的文件名（放在程序目录或用户目录）。 */
    private static final String PATH_FILE = "gamepath.txt";

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
        Set<Path> seen = new LinkedHashSet<>();

        addCandidate(out, seen, runningGameExe());
        for (Path configured : configuredExePaths()) {
            addCandidate(out, seen, configured);
        }

        Path program = programDirectory();
        Path working = Paths.get("").toAbsolutePath();
        for (Path base : new Path[]{program, working}) {
            if (base != null) {
                addAncestors(base, out, seen);
            }
        }

        for (String drive : new String[]{"D:", "E:", "F:", "C:"}) {
            addCommonRoots(drive, out, seen);
        }
        return out;
    }

    /**
     * 从某个目录出发逐级向上找：每级都看一眼本级的 exe 和形如
     * {@code XiewaPai 1.0.3} 的子目录。
     *
     * @param base 起点
     * @param out  输出列表
     * @param seen 去重集合
     */
    private static void addAncestors(Path base, List<Path> out, Set<Path> seen) {
        Path directory = base;
        for (int level = 0; level < MAX_ANCESTOR_LEVELS && directory != null; level++) {
            addCandidate(out, seen, directory.resolve(GAME_EXE));
            for (Path exe : siblingGameDirs(directory)) {
                addCandidate(out, seen, exe);
            }
            directory = directory.getParent();
        }
    }

    /**
     * 扫描某个盘符下常见的游戏根目录。
     *
     * @param drive 盘符，例如 {@code F:}
     * @param out   输出列表
     * @param seen  去重集合
     */
    private static void addCommonRoots(String drive, List<Path> out, Set<Path> seen) {
        Path root;
        try {
            root = Paths.get(drive + "\\");
        } catch (RuntimeException e) {
            return;
        }
        if (!Files.isDirectory(root)) {
            return;
        }

        addCandidate(out, seen, root.resolve(GAME_EXE));
        for (Path exe : siblingGameDirs(root)) {
            addCandidate(out, seen, exe);
        }
        for (String name : GAME_ROOT_NAMES) {
            Path base = root.resolve(name);
            if (!Files.isDirectory(base)) {
                continue;
            }
            for (Path exe : findExeRecursively(base, "功夫派".equals(name) ? 6 : 4)) {
                addCandidate(out, seen, exe);
            }
        }
    }

    /**
     * 在目录树里找 {@code XiewaPai.exe}，结果按修改时间倒序（新版本优先）。
     *
     * @param base     根目录
     * @param maxDepth 最大深度
     * @return exe 列表
     */
    private static List<Path> findExeRecursively(Path base, int maxDepth) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(base, maxDepth)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> GAME_EXE.equalsIgnoreCase(path.getFileName().toString()))
                    .filter(GameLocator::isPlausibleLocation)
                    .forEach(found::add);
        } catch (IOException | SecurityException ignored) {
            // 目录不可读就返回已经找到的部分
        }
        found.sort(Comparator.comparingLong(GameLocator::lastModified).reversed());
        return found;
    }

    /**
     * 排除备份、回收站等不该被当成游戏本体的位置。
     *
     * @param exe exe 路径
     * @return 可信返回 true
     */
    private static boolean isPlausibleLocation(Path exe) {
        String text = exe.toString().toLowerCase();
        return !text.contains("\\backup\\")
                && !text.contains("\\存档备份\\")
                && !text.contains("\\$recycle.bin\\")
                && !text.contains("\\system volume information\\");
    }

    /**
     * 正在运行的游戏进程路径。
     *
     * @return exe 路径；未运行或无权读取时返回 {@code null}
     */
    private static Path runningGameExe() {
        try {
            Optional<Path> path = ProcessHandle.allProcesses()
                    .map(ProcessHandle::info)
                    .map(ProcessHandle.Info::command)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(GameLocator::stripQuotes)
                    .filter(text -> text.toLowerCase().endsWith(GAME_EXE.toLowerCase()))
                    .map(Path::of)
                    .filter(Files::isRegularFile)
                    .findFirst();
            return path.orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 手动配置的 exe 路径。
     *
     * @return 候选列表（可能为空）
     */
    private static List<Path> configuredExePaths() {
        List<Path> out = new ArrayList<>();
        String env = System.getenv("XIEWAPAI_EXE");
        if (env != null && !env.isBlank()) {
            out.add(normalizeConfigured(env.trim()));
        }
        for (Path file : configFiles()) {
            try {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                for (String line : Files.readAllLines(file)) {
                    if (!line.isBlank() && !line.trim().startsWith("#")) {
                        out.add(normalizeConfigured(line.trim()));
                        break;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 配置读不了就跳过
            }
        }
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    /**
     * 配置文件候选位置。
     *
     * @return 文件列表
     */
    private static List<Path> configFiles() {
        List<Path> files = new ArrayList<>();
        files.add(programDirectory().resolve(PATH_FILE));
        files.add(Paths.get("").toAbsolutePath().resolve(PATH_FILE));
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            files.add(Paths.get(appData, "chengyi-updatepai", PATH_FILE));
        }
        return files;
    }

    /**
     * 把配置里的值统一成 exe 路径：目录则补上文件名。
     *
     * @param value 用户配置
     * @return exe 路径；无法解析返回 {@code null}
     */
    private static Path normalizeConfigured(String value) {
        try {
            Path path = Paths.get(stripQuotes(value));
            if (Files.isDirectory(path)) {
                return path.resolve(GAME_EXE);
            }
            return path;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stripQuotes(String text) {
        String out = text.trim();
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1);
        }
        return out;
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
     * 扫描某个目录下形如 {@code XiewaPai 1.0.3} 的子目录，新版本优先。
     */
    private static List<Path> siblingGameDirs(Path base) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (var stream = Files.list(base)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(GAME_DIR_PREFIX))
                    .map(p -> p.resolve(GAME_EXE))
                    .filter(Files::isRegularFile)
                    .forEach(out::add);
        } catch (Exception ignored) {
            // 目录不可读就跳过
        }
        out.sort(Comparator.comparingLong(GameLocator::lastModified).reversed());
        return out;
    }

    private static void addCandidate(List<Path> out, Set<Path> seen, Path candidate) {
        if (candidate == null) {
            return;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (seen.add(normalized)) {
            out.add(normalized);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}