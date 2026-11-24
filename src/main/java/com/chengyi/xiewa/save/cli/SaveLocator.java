package com.chengyi.xiewa.save.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 定位存档文件与密钥文件。
 *
 * <p>存档固定位于 {@code %APPDATA%\Ombrel\rt\saves\<账号ID>\save.dat}，
 * 一台机器上可能有多个账号目录，默认取最近修改的那份。
 */
public final class SaveLocator {

    /** 存档相对路径模板。 */
    private static final String SAVES_RELATIVE = "Ombrel/rt/saves";

    /** 存档文件名。 */
    private static final String SAVE_FILE_NAME = "save.dat";

    /** 密钥文件名。 */
    private static final String KEY_FILE_NAME = "keys.json";

    private SaveLocator() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 返回存档根目录。
     *
     * @return {@code %APPDATA%\Ombrel\rt\saves}
     * @throws SaveFormatException 取不到 APPDATA 环境变量
     */
    public static Path savesRoot() throws SaveFormatException {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            throw new SaveFormatException("取不到 APPDATA 环境变量，无法定位存档目录");
        }
        return Paths.get(appData).resolve(SAVES_RELATIVE);
    }

    /**
     * 扫描全部账号存档，按修改时间倒序。
     *
     * @return 存档路径列表
     * @throws SaveFormatException 目录不存在
     */
    public static List<Path> listAll() throws SaveFormatException {
        Path root = savesRoot();
        if (!Files.isDirectory(root)) {
            throw new SaveFormatException("存档目录不存在：" + root);
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .map(dir -> dir.resolve(SAVE_FILE_NAME))
                    .filter(Files::isRegularFile)
                    .forEach(found::add);
        } catch (IOException e) {
            throw new SaveFormatException("扫描存档目录失败：" + root, e);
        }
        found.sort(Comparator.comparingLong(SaveLocator::lastModified).reversed());
        return found;
    }

    /**
     * 取最近修改的存档。
     *
     * @return 存档路径
     * @throws SaveFormatException 一个存档都找不到
     */
    public static Path defaultSave() throws SaveFormatException {
        List<Path> all = listAll();
        if (all.isEmpty()) {
            throw new SaveFormatException("在 " + savesRoot() + " 下没有找到任何 save.dat");
        }
        return all.get(0);
    }

    /**
     * 从存档路径推断账号 ID（即所在目录名）。
     *
     * @param save 存档路径
     * @return 账号 ID
     */
    public static String profileIdOf(Path save) {
        Path parent = save.toAbsolutePath().getParent();
        return parent == null ? "" : parent.getFileName().toString();
    }

    /**
     * keys.json 的候选位置，按优先级排列：工作目录 → 程序所在目录 → 上级的「修改器」目录。
     *
     * @return 候选路径数组
     */
    public static Path[] keyFileCandidates() {
        Path working = Paths.get("").toAbsolutePath();
        Path program = programDirectory();
        return new Path[]{
                working.resolve(KEY_FILE_NAME),
                program.resolve(KEY_FILE_NAME),
                program.getParent() == null ? null : program.getParent().resolve("修改器").resolve(KEY_FILE_NAME),
                working.getParent() == null ? null : working.getParent().resolve("修改器").resolve(KEY_FILE_NAME),
        };
    }

    /**
     * 程序自身所在目录（打包运行时是 exe 目录，开发时是项目根）。
     *
     * @return 目录
     */
    public static Path programDirectory() {
        try {
            Path location = Paths.get(
                    SaveLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            return dir == null ? Paths.get("").toAbsolutePath() : dir.toAbsolutePath();
        } catch (Exception e) {
            return Paths.get("").toAbsolutePath();
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
