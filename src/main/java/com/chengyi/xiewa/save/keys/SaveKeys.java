package com.chengyi.xiewa.save.keys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 密钥解析总入口。
 *
 * <p>目标是让修改器<b>拷到任何电脑上、选中存档就能直接改</b>，用户完全不需要
 * 关心"密钥"这件事。为此按下列顺序收集候选，交给调用方逐个试解：
 * <ol>
 *   <li><b>本机 DPAPI</b>：从游戏 exe 取熵、用当前 Windows 凭据解 {@code profile.cfg}，
 *       在本机产生存档时这条永远最快最准</li>
 *   <li><b>密钥库</b>：{@link KeyVault}，含 jar 内置的一份 + 程序目录 keyvault.json，
 *       这是换电脑后唯一能走通的路</li>
 *   <li><b>外部 keys.json</b>：兼容旧版工具留下的文件，存在就用，不存在也不影响</li>
 * </ol>
 *
 * <p>为什么不能"纯自动算出密钥"：存档用 AES-256 加密，密钥是游戏随机生成的 32 字节，
 * 不以任何可推导的形式存在于存档里。离开原机后必须靠一份保存下来的副本，
 * 因此本类的设计重点是「把副本藏进程序自己身上」，而不是「算出副本」。
 *
 * <p>结果按账号 ID 缓存，避免每次操作都重跑 DPAPI。
 */
public final class SaveKeys {

    /** 密钥的来源说明，用于界面与日志展示。 */
    public enum Source {
        /** 本机 DPAPI 直接解出。 */
        LOCAL_DPAPI("本机 DPAPI"),
        /** 来自随程序携带的密钥库。 */
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
                .append("只存在产生它的那台电脑上。\n")
                .append("解决办法：在产生这份存档的那台电脑上打开本程序一次（会自动记住密钥），")
                .append("再把整个「chengyi-updatepai」文件夹拷过来即可。");
        throw new SaveFormatException(message.toString());
    }

    /**
     * 列出这个存档的全部候选密钥，按"最可能正确"排序。
     *
     * <p>顺序：① 已确认成功过的（缓存）② 本机 DPAPI ③ 密钥库里同账号的
     * ④ 密钥库里其他账号的 ⑤ 外部 keys.json。后两档是为了兼容
     * "存档从别的电脑拷过来、目录名已不是原账号 ID"的情况——挨个试总比直接报错好。
     *
     * @param savePath 存档路径
     * @return 候选列表（可能为空）
     */
    public static List<Resolution> candidates(Path savePath) {
        return collectCandidates(savePath, new ArrayList<>());
    }

    /**
     * 记住某个密钥确实能解开这份存档，并顺手存进密钥库。
     *
     * <p>这一步是"跨机可用"的关键：无论密钥来自 DPAPI、内置库还是外部文件，
     * 只要被验证为正确，就立即写入程序目录的 keyvault.json，
     * 之后整个文件夹搬到哪台电脑都能直接打开同一份存档。
     *
     * @param savePath 存档路径
     * @param working  试解成功的解析结果
     */
    public static void remember(Path savePath, Resolution working) {
        String profileId = profileIdOf(savePath);
        CACHE.put(profileId, working);
        KeyVault.store(profileId, working.key());
    }

    /**
     * 扫描本机全部游戏账号，把能解开的密钥批量存进密钥库。
     *
     * <p>在装有游戏的电脑上跑一次，之后把文件夹拷走就能在其他机器上改这些账号的存档。
     * 由界面启动时在后台线程调用，没有游戏是正常情况，失败不提示。
     *
     * @return 本次新收录的密钥条数
     */
    public static int harvest() {
        LocalKeyProvider local = localProvider();
        if (local == null) {
            return 0;
        }
        Path root;
        try {
            root = LocalKeyProvider.savesRoot();
        } catch (SaveFormatException e) {
            return 0;
        }
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int stored = 0;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                if (!ProfileKeyReader.hasProfile(directory)) {
                    continue;
                }
                try {
                    byte[] key = local.keyForDirectory(directory);
                    if (KeyVault.store(directory.getFileName().toString(), key)) {
                        stored++;
                    }
                } catch (SaveFormatException ignored) {
                    // 单个账号解不开不影响其他账号，静默跳过
                }
            }
        } catch (IOException e) {
            return stored;
        }
        return stored;
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
                addCandidate(out, seen, new Resolution(key, Source.LOCAL_DPAPI,
                        local.gameExe().toString()));
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
            errors.add("密钥库：内置库与本地库都是空的");
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
            return "本机 DPAPI 可用（游戏：%s）".formatted(provider.gameExe().getFileName());
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
            return "密钥库为空";
        }
        return "%d 把（%s）".formatted(total, KeyVault.describe());
    }

    private static synchronized LocalKeyProvider localProvider() {
        if (!localChecked) {
            localChecked = true;
            try {
                localProvider = LocalKeyProvider.tryCreate();
                if (localProvider == null) {
                    localFailure = "未找到 XiewaPai.exe";
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
