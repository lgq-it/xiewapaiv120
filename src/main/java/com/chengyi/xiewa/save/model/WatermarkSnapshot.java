package com.chengyi.xiewa.save.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 修改器每次写档前，把「改动之前」的完整状态打包进一个时间戳文件夹。
 *
 * <p>游戏 1.0.9 起引入了「存档水位账本」+「机器封禁」两套<b>存档之外的</b>状态，
 * 它们与 save.dat 互相校验、互相 OR（{@code merge_guard: sealed = a.sealed or b.sealed}）。
 * 只备份存档、不备份这些外部状态，改坏之后就做不到「完美还原」——水位记录被
 * 只增不减的代数卡住，机器封禁记录 XWDL3 更是一旦写回就无法靠改存档解除。
 *
 * <p>所以这里做<b>全量快照</b>，把一次写档涉及的全部状态收进同一个时间戳目录：
 * <ul>
 *   <li>{@code save.dat} 改动前的原档；</li>
 *   <li>{@code save.dat.bak} 若存在，一并收（它是游戏亲手写的上一份原档）；</li>
 *   <li>{@code registry-cache.reg} {@code HKCU\Software\Ombrel\Cache} 整个键的导出，
 *       含水位记录、机器封禁记录、检查点等所有值（还原时双击导入即可）；</li>
 *   <li>{@code localappdata-cache\*} {@code %LOCALAPPDATA%\Ombrel\cache} 整个目录
 *       （水位 .idx 与机器封禁 .idx 的第二处镜像）；</li>
 *   <li>{@code programdata-cache\*} {@code %PROGRAMDATA%\Ombrel\cache} 整个目录
 *       （第三处镜像，漏掉它 {@code present_count} 会不齐）。</li>
 * </ul>
 *
 * <p>清理策略：只保留最近 {@link #RETENTION_DAYS} 天的时间戳文件夹，更早的整目录删除。
 * 任何一步失败都不抛异常——快照只是保险，不能因为它挡住正常的保存流程。
 */
public final class WatermarkSnapshot {

    /** 时间戳文件夹的命名格式（可排序，也方便肉眼分辨日期时刻）。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 时间戳文件夹保留天数。 */
    private static final long RETENTION_DAYS = 2;

    private WatermarkSnapshot() {
    }

    /**
     * 给指定存档做一次「改动前」的全量快照，写到存档目录的 {@code backup/<时间戳>/} 里。
     *
     * @param savePath save.dat 的路径
     * @return 快照文件夹路径；失败或没有可备份内容时返回 {@code null}
     */
    public static Path snapshot(Path savePath) {
        try {
            Path profileDir = savePath.toAbsolutePath().getParent();
            if (profileDir == null) {
                return null;
            }
            // 备份不能落在游戏会扫描的存档目录里：native editor_check 会把它当成
            // “编辑器痕迹”从而重建机器封禁。这里改放到 %LOCALAPPDATA% 下的独立目录。
            String profileId = profileDir.getFileName().toString();
            Path backupRoot = Path.of(envOrEmpty("LOCALAPPDATA"), "chengyi-updatepai",
                    "backups", profileId);
            Files.createDirectories(backupRoot);
            String stamp = LocalDateTime.now().format(STAMP);
            Path snapshotDir = backupRoot.resolve(stamp);
            Files.createDirectories(snapshotDir);

            // 1) 存档原档（改动之前）
            copyIfExists(savePath, snapshotDir.resolve("save.dat"));
            copyIfExists(profileDir.resolve("save.dat.bak"), snapshotDir.resolve("save.dat.bak"));

            // 2) 注册表：整个 Cache 键导出（含水位 + 机器封禁 + 检查点）
            exportRegistryCache(snapshotDir.resolve("registry-cache.reg"));

            // 3) LOCALAPPDATA 缓存目录（水位/封禁第二处镜像）
            Path localCache = Path.of(envOrEmpty("LOCALAPPDATA"), "Ombrel", "cache");
            copyDirectory(localCache, snapshotDir.resolve("localappdata-cache"));

            // 4) PROGRAMDATA 缓存目录（水位/封禁第三处镜像）
            Path programCache = Path.of(envOrEmpty("PROGRAMDATA"), "Ombrel", "cache");
            copyDirectory(programCache, snapshotDir.resolve("programdata-cache"));

            pruneOldSnapshots(backupRoot);
            return snapshotDir;
        } catch (Exception ignored) {
            // 快照失败不影响保存
            return null;
        }
    }

    /**
     * 列出指定存档目录下所有可还原的备份点（时间戳文件夹），按时间从新到旧排序。
     *
     * @param savePath save.dat 的路径
     * @return 备份点目录列表；没有备份时返回空列表
     */
    public static List<Path> listBackups(Path savePath) {
        Path profileDir = savePath.toAbsolutePath().getParent();
        if (profileDir == null) {
            return List.of();
        }
        String profileId = profileDir.getFileName().toString();
        Path backupRoot = Path.of(envOrEmpty("LOCALAPPDATA"), "chengyi-updatepai",
                "backups", profileId);
        if (!Files.isDirectory(backupRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(backupRoot)) {
            return stream.filter(Files::isDirectory)
                    .filter(WatermarkSnapshot::looksLikeSnapshot)
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 把一个备份点完整还原回当前存档目录（存档 + 注册表 Cache 键 + 两处缓存镜像）。
     *
     * @param snapshotDir 由 {@link #listBackups(Path)} 返回的备份点目录
     * @param savePath    save.dat 的路径
     * @throws IOException          文件复制失败
     * @throws InterruptedException 注册表导入被中断
     */
    public static void restore(Path snapshotDir, Path savePath)
            throws IOException, InterruptedException {
        Path profileDir = savePath.toAbsolutePath().getParent();
        if (profileDir == null) {
            throw new IOException("无法确定存档所在目录：" + savePath);
        }

        Path snapSave = snapshotDir.resolve("save.dat");
        if (Files.isRegularFile(snapSave)) {
            Files.copy(snapSave, savePath, StandardCopyOption.REPLACE_EXISTING);
        }

        Path reg = snapshotDir.resolve("registry-cache.reg");
        if (Files.isRegularFile(reg)) {
            Process process = new ProcessBuilder("reg", "import", reg.toString())
                    .redirectErrorStream(true)
                    .start();
            process.waitFor();
        }

        restoreCache(snapshotDir.resolve("localappdata-cache"),
                Path.of(envOrEmpty("LOCALAPPDATA"), "Ombrel", "cache"));
        restoreCache(snapshotDir.resolve("programdata-cache"),
                Path.of(envOrEmpty("PROGRAMDATA"), "Ombrel", "cache"));
        // 快照里可能带有机封记录 XWDL3；还原之后再清一次，保证机器封禁不随
        // 备份一起复活，否则存档与水位都回滚了，开档仍会被判成 x8。
        MachineSeal.clear();
    }

    /** 把快照里的缓存目录内容覆盖回目标缓存目录。 */
    private static void restoreCache(Path source, Path target) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            return;
        }
        Files.createDirectories(target);
        try (Stream<Path> stream = Files.list(source)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Files.copy(file, target.resolve(file.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 用系统 {@code reg.exe export} 导出整个 Cache 键，失败静默跳过。 */
    private static void exportRegistryCache(Path target) {
        try {
            Process process = new ProcessBuilder(
                    "reg", "export",
                    "HKCU\\Software\\Ombrel\\Cache",
                    target.toString(),
                    "/y")
                    .redirectErrorStream(true)
                    .start();
            process.waitFor();
        } catch (Exception ignored) {
            // reg 不可用或键不存在：跳过，其余部分仍能还原
        }
    }

    /** 目录整体复制到目标；源不存在或为空时静默跳过。 */
    private static void copyDirectory(Path source, Path target) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> stream = Files.list(source)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            if (files.isEmpty()) {
                return;
            }
            Files.createDirectories(target);
            for (Path file : files) {
                Files.copy(file, target.resolve(file.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 文件存在就复制，不存在静默跳过。 */
    private static void copyIfExists(Path source, Path target) {
        try {
            if (Files.isRegularFile(source)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // 单个文件复制失败不影响其余快照
        }
    }

    /** 删除修改时间早于保留期的<b>时间戳文件夹</b>（只按文件夹名过滤，不碰其他文件）。 */
    private static void pruneOldSnapshots(Path backupRoot) throws IOException {
        Instant deadline = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        try (Stream<Path> stream = Files.list(backupRoot)) {
            List<Path> expired = stream
                    .filter(Files::isDirectory)
                    .filter(WatermarkSnapshot::looksLikeSnapshot)
                    .filter(dir -> {
                        try {
                            FileTime time = Files.getLastModifiedTime(dir);
                            return time.toInstant().isBefore(deadline);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
            for (Path dir : expired) {
                deleteRecursively(dir);
            }
        }
    }

    /** 文件夹名形如 {@code yyyyMMdd-HHmmss} 才算时间戳快照，避免误删别的目录。 */
    private static boolean looksLikeSnapshot(Path dir) {
        String name = dir.getFileName().toString();
        return name.matches("\\d{8}-\\d{6}");
    }

    /** 递归删除目录树（快照目录是我们自己建的，里面只有副本文件，删除是安全清理）。 */
    private static void deleteRecursively(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 个别文件占用删不掉就留着，下次再清
                        }
                    });
        } catch (IOException ignored) {
            // 遍历失败就不清
        }
    }

    private static String envOrEmpty(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }
}
