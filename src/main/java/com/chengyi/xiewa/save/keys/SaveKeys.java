package com.chengyi.xiewa.save.keys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 密钥解析总入口。
 *
 * <p>程序读取存档时直接用当前 Windows 用户的本机 DPAPI 解 {@code profile.cfg}，
 * 密钥只放在内存缓存里，<b>不写盘、不打包、不收集</b>。
 * 1.1.1 起的新版 {@code XWD3} 信封可离线解；更早的旧格式需要游戏 exe 推导熵。
 *
 * <p>为什么不能"纯自动算出密钥"：存档用 AES-256 加密，密钥是游戏随机生成的 32 字节，
 * 不以任何可推导的形式存在于存档里，只存在于当前电脑、当前 Windows 用户的 DPAPI 保护下。
 * 因此换电脑或换 Windows 用户时，必须在那台机器上重新登录对应账号，程序无法凭空解出别人的存档。
 *
 * <p>结果按账号 ID 缓存，避免每次操作都重跑 DPAPI。
 */
public final class SaveKeys {

    /** 密钥的来源说明，用于界面与日志展示。 */
    public enum Source {
        /** 本机 DPAPI 直接解出。 */
        LOCAL_DPAPI("本机 DPAPI"),
        /** 来自用户自行放置的密钥库。 */
        KEY_VAULT("密钥库"),
        /** 来自外部 keys.json（旧版工具遗留）。 */
        KEY_FILE("keys.json");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /**
         * 中文名称。
         *
         * @return 名称
         */
        public String label() {
            return label;
        }
    }

    /**
     * 解析结果。
     *
     * @param key    32 字节密钥
     * @param source 来源
     * @param detail 附加说明（例如 exe 路径、密钥库路径）
     */
    public record Resolution(byte[] key, Source source, String detail) {
    }

    private static final Map<String, Resolution> CACHE = new HashMap<>();

    private static LocalKeyProvider localProvider;
    private static boolean localChecked;
    private static String localFailure = "";

    private SaveKeys() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 取某个存档文件"最可能正确"的密钥（不验证能否解开）。
     *
     * <p>需要"试到能解开为止"时请用 {@link #candidates(Path)}，它能返回全部备选。
     *
     * @param savePath save.dat 路径（用所在目录名作为账号 ID）
     * @return 解析结果
     * @throws SaveFormatException 没有任何可用来源
     */
    public static Resolution resolve(Path savePath) throws SaveFormatException {
        List<String> errors = new ArrayList<>();
        List<Resolution> candidates = collectCandidates(savePath, errors);
        if (!candidates.isEmpty()) {
            return copyOf(candidates.get(0));
        }
        StringBuilder message = new StringBuilder("拿不到这份存档的密钥。");
        message.append("\n存档：").append(savePath.toAbsolutePath());
        for (String error : errors) {
            message.append('\n').append(error);
        }
        message.append("\n\n这不是程序出错：存档是 AES 加密的，密钥由游戏随机生成，")
                .append("只存在产生它的那台电脑、那个 Windows 用户的 DPAPI 下；本程序不收集、不保存密钥。\n")
                .append("解决办法：在产生这份存档的那台电脑上，用同一个 Windows 用户运行本程序。");
        throw new SaveFormatException(message.toString());
    }

    /**
     * 列出这个存档的全部候选密钥，按"最可能正确"排序。
     *
     * <p>顺序：① 已确认成功过的（缓存）② 本机 DPAPI。
     * 如果用户手动放了一份外部 keyvault.json / keys.json，也会追加为兜底候选；
     * 程序自身不会生成或写入这些文件。
     *
     * @param savePath 存档路径
     * @return 候选列表（可能为空）
     */
    public static List<Resolution> candidates(Path savePath) {
        return collectCandidates(savePath, new ArrayList<>());
    }

    /**
     * 记住某个密钥确实能解开这份存档。
     *
     * <p>只放在内存缓存里，避免每次操作都重跑 DPAPI；密钥不写盘、不收集。
     *
     * @param savePath 存档路径
     * @param working  试解成功的解析结果
     */
    public static void remember(Path savePath, Resolution working) {
        CACHE.put(profileIdOf(savePath), working);
    }
    /**
     * 收集候选密钥的实现。
     *
     * @param savePath 存档路径
     * @param errors   输出参数：各来源的失败原因（{@link #resolve} 用来拼错误提示）
     * @return 候选列表
     */
    private static List<Resolution> collectCandidates(Path savePath, List<String> errors) {
        String profileId = profileIdOf(savePath);
        Resolution cached = CACHE.get(profileId);
        if (cached != null) {
            return List.of(cached);
        }

        List<Resolution> out = new ArrayList<>();
        // 同一个密钥可能同时存在于内置库和外部文件，去重避免重复试解
        Set<String> seen = new LinkedHashSet<>();

        // ① 本机 DPAPI
        Path profileDirectory = savePath.toAbsolutePath().getParent();
        LocalKeyProvider local = localProvider();
        if (local != null && profileDirectory != null) {
            try {
                byte[] key = local.keyForDirectory(profileDirectory);
                String detail = local.gameExe() == null
                        ? "本机 DPAPI（XWD3 信封）"
                        : local.gameExe().toString();
                addCandidate(out, seen, new Resolution(key, Source.LOCAL_DPAPI, detail));
            } catch (SaveFormatException e) {
                errors.add("本机 DPAPI：" + e.getMessage());
            }
        } else if (!localFailure.isEmpty()) {
            errors.add("本机 DPAPI：" + localFailure);
        }

        // ② 密钥库（内置 + 程序目录 + 用户目录）：同账号优先，其余账号兜底
        Map<String, List<byte[]>> vault = KeyVault.load();
        List<byte[]> exact = vault.get(profileId);
        if (exact != null) {
            for (byte[] key : exact) {
                addCandidate(out, seen, new Resolution(key, Source.KEY_VAULT,
                        "密钥库（账号 " + profileId + "）"));
            }
        }
        for (Map.Entry<String, List<byte[]>> entry : vault.entrySet()) {
            if (entry.getKey().equals(profileId)) {
                continue;
            }
            for (byte[] key : entry.getValue()) {
                addCandidate(out, seen, new Resolution(key, Source.KEY_VAULT,
                        "密钥库（账号 " + entry.getKey() + "）"));
            }
        }
        if (vault.isEmpty()) {
            errors.add("密钥库：未使用（仅本机 DPAPI 实时解析）");
        }

        // ③ 外部 keys.json（旧版工具生成，存在就兼容）
        try {
            KeyJsonProvider json = KeyJsonProvider.loadFirstAvailable(keyFileCandidates());
            Map<String, byte[]> all = json.allKeys();
            byte[] own = all.get(profileId);
            if (own != null) {
                addCandidate(out, seen, new Resolution(own, Source.KEY_FILE,
                        json.describeSource() + "（账号 " + profileId + "）"));
            }
            for (Map.Entry<String, byte[]> entry : all.entrySet()) {
                if (entry.getKey().equals(profileId)) {
                    continue;
                }
                addCandidate(out, seen, new Resolution(entry.getValue(), Source.KEY_FILE,
                        json.describeSource() + "（账号 " + entry.getKey() + "）"));
            }
        } catch (SaveFormatException e) {
            // 没有外部文件是常态，不记为错误，免得干扰提问
        }
        return out;
    }

    /**
     * 加入候选，同时按密钥十六进制去重。
     *
     * @param out       候选列表
     * @param seen      已加入的密钥指纹
     * @param candidate 待加入项
     */
    private static void addCandidate(List<Resolution> out, Set<String> seen, Resolution candidate) {
        String fingerprint = java.util.HexFormat.of().formatHex(candidate.key());
        if (seen.add(fingerprint)) {
            out.add(candidate);
        }
    }

    /**
     * 复制一份解析结果，避免调用方改到缓存里的数组。
     *
     * @param source 原结果
     * @return 副本
     */
    private static Resolution copyOf(Resolution source) {
        return new Resolution(source.key().clone(), source.source(), source.detail());
    }

    /**
     * 清空缓存（切换存档目录或想重新探测时调用）。
     */
    public static void clearCache() {
        CACHE.clear();
        localProvider = null;
        localChecked = false;
        localFailure = "";
    }

    /**
     * 本机 DPAPI 是否可用（用于界面状态提示）。
     *
     * @return 可用返回 true
     */
    public static boolean localAvailable() {
        return localProvider() != null;
    }

    /**
     * 本机来源不可用的原因（可用时为空串）。
     *
     * @return 说明
     */
    public static String localStatusText() {
        LocalKeyProvider provider = localProvider();
        if (provider != null) {
            Path exe = provider.gameExe();
            String game = exe == null
                    ? "未找到 XiewaPai.exe，仅支持新版存档"
                    : exe.getFileName().toString();
            return provider.hasLegacyEntropy()
                    ? "本机 DPAPI 可用（游戏：%s）".formatted(game)
                    : "本机 DPAPI 可用（%s）".formatted(game);
        }
        return localFailure.isEmpty() ? "本机 DPAPI 不可用" : "本机 DPAPI 不可用：" + localFailure;
    }

    /**
     * 密钥库概况，用于状态栏与日志。
     *
     * @return 例如「3 把（内置 1 把 + keyvault.json @ ...）」
     */
    public static String vaultStatusText() {
        int total = KeyVault.totalKeyCount();
        if (total == 0) {
            return "未使用密钥库（仅本机 DPAPI 实时解析）";
        }
        return "检测到外部密钥库 %d 把（%s）".formatted(total, KeyVault.describe());
    }

    private static synchronized LocalKeyProvider localProvider() {
        if (!localChecked) {
            localChecked = true;
            try {
                localProvider = LocalKeyProvider.tryCreate();
                if (localProvider == null) {
                    localFailure = "当前系统不支持 DPAPI";
                }
            } catch (SaveFormatException e) {
                localFailure = e.getMessage();
            }
        }
        return localProvider;
    }

    /**
     * 外部 keys.json 的候选位置：程序目录（含上级「修改器」目录）、工作目录。
     *
     * @return 候选路径
     */
    public static Path[] keyFileCandidates() {
        Path program = GameLocator.programDirectory();
        Path working = Path.of("").toAbsolutePath();
        Path parent = program.getParent();
        Path workingParent = working.getParent();
        return new Path[]{
                program.resolve("keys.json"),
                working.resolve("keys.json"),
                parent == null ? null : parent.resolve("keys.json"),
                parent == null ? null : parent.resolve("修改器").resolve("keys.json"),
                workingParent == null ? null : workingParent.resolve("keys.json"),
        };
    }

    /**
     * 从存档路径推断账号 ID。
     *
     * @param savePath 存档路径
     * @return 账号 ID
     */
    public static String profileIdOf(Path savePath) {
        Path parent = savePath.toAbsolutePath().getParent();
        return parent == null ? "" : parent.getFileName().toString();
    }

    /**
     * 是否存在可用的外部 keys.json（界面提示用；新版本已不依赖它）。
     *
     * @return 存在返回 true
     */
    public static boolean keyFileAvailable() {
        for (Path candidate : keyFileCandidates()) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return true;
            }
        }
        return false;
    }
}
