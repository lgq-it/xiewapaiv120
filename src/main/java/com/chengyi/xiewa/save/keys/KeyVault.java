package com.chengyi.xiewa.save.keys;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 随程序携带的「密钥库」——让修改器拷到任意电脑上都能直接打开存档。
 *
 * <p><b>为什么需要它。</b>存档用 AES-256 加密，密钥是游戏随机生成的 32 字节，
 * 只以两种形态存在：原机 Windows 凭据保护的 {@code profile.cfg}，或事先导出的明文。
 * 换一台电脑后前者必然解不开（DPAPI 的 master key 与「机器 + Windows 用户」绑定），
 * 所以要跨机使用，程序必须自带一份"钥匙复印件"。
 *
 * <p><b>三个来源，按优先级合并：</b>
 * <ol>
 *   <li><b>内置</b>：打包进 jar 的 {@code /keyvault.json}，删不掉、丢不了</li>
 *   <li><b>程序目录</b>：{@code chengyi-updatepai\keyvault.json}，跟着文件夹一起搬走</li>
 *   <li><b>用户目录</b>：{@code %APPDATA%\chengyi-updatepai\keyvault.json}，程序目录不可写时的退路</li>
 * </ol>
 *
 * <p><b>自动积累。</b>只要在任一机器上成功解开过某个账号，该密钥就会被
 * {@link #store(String, byte[])} 追加进可写的那份文件；之后整个文件夹搬到哪里都能用，
 * 用户全程不需要知道"密钥"这回事。
 *
 * <p>同一账号允许多把密钥并存（游戏重装后会生成新密钥，而旧存档仍可能需要旧密钥），
 * 因此内部结构是 {@code 账号ID -> [密钥...]}，而非一对一映射。
 */
public final class KeyVault {

    /** 内置资源路径（在 jar 根目录）。 */
    private static final String RESOURCE = "/keyvault.json";

    /** 密钥文件名。 */
    private static final String FILE_NAME = "keyvault.json";

    /** 用户目录下的子文件夹名（与发布包同名，方便用户对照查找）。 */
    private static final String APP_FOLDER = "chengyi-updatepai";

    /** 旧版本用过的子文件夹名：只读兼容，升级后仍能读到此前积累的密钥。 */
    private static final String LEGACY_APP_FOLDER = "功夫派修改器";

    /** 数组形态：{@code "账号": ["hex", "hex"]}。 */
    private static final Pattern ARRAY_ENTRY =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]");

    /** 单值形态（兼容旧 keys.json）：{@code "账号": "hex"}。 */
    private static final Pattern SCALAR_ENTRY =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([0-9a-fA-F]{64})\"");

    /** 从任意文本中抠出 64 位十六进制串。 */
    private static final Pattern HEX64 = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");

    private KeyVault() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 读取全部可用密钥（内置 + 程序目录 + 用户目录，自动去重）。
     *
     * @return 账号 ID → 密钥列表（保持读取顺序）
     */
    public static Map<String, List<byte[]>> load() {
        Map<String, List<byte[]>> merged = new LinkedHashMap<>();
        mergeInto(merged, readEmbedded());
        for (Path file : readableFiles()) {
            mergeInto(merged, parseFile(file));
        }
        return merged;
    }

    /**
     * 追加一把密钥进可写密钥库。
     *
     * <p>两条过滤规则，避免库里长出一堆垃圾条目：
     * <ul>
     *   <li>账号 ID 必须是纯数字（游戏账号目录名的格式）。手动选桌面上的 save.dat 时，
     *       目录名会是 {@code Desktop} 这类名字，那种情况不记录——反正内置库已经能解开</li>
     *   <li>密钥只要在任意位置已经存在就不再重复记（换目录名、换账号目录拷来拷去很常见）</li>
     * </ul>
     *
     * @param profileId 账号 ID（纯数字）
     * @param key       32 字节密钥
     * @return 真正写入返回 true（已存在、名字不合法或写入失败返回 false）
     */
    public static synchronized boolean store(String profileId, byte[] key) {
        if (profileId == null || !profileId.matches("\\d+") || key == null || key.length != 32) {
            return false;
        }
        if (containsAnywhere(key)) {
            // 这把密钥已经能用了，不必再按新键名记一遍
            return false;
        }
        Path target = writablePath();
        if (target == null) {
            return false;
        }
        // 复制成可变表：文件不存在时 parseFile 可能返回不可变空表，直接 computeIfAbsent 会抛异常
        Map<String, List<byte[]>> existing = new LinkedHashMap<>(parseFile(target));
        existing.computeIfAbsent(profileId, id -> new ArrayList<>()).add(key.clone());
        return writeFile(target, existing);
    }

    /**
     * 判断某把密钥是否已经存在于任一密钥库（内置 / 程序目录 / 用户目录）。
     *
     * @param key 待查密钥
     * @return 已存在返回 true
     */
    private static boolean containsAnywhere(byte[] key) {
        for (List<byte[]> keys : load().values()) {
            if (contains(keys, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 可写密钥库的实际路径：优先程序目录（跟着文件夹走），其次用户目录。
     *
     * @return 路径；两处都不可用时返回 {@code null}
     */
    public static Path writablePath() {
        Path program = programKeyFile();
        if (program != null) {
            Path directory = program.getParent();
            if (directory != null && Files.isDirectory(directory) && Files.isWritable(directory)) {
                // 开发环境下不写项目根，免得污染工程目录
                if (!Files.isRegularFile(directory.resolve("pom.xml"))) {
                    return program;
                }
            }
        }
        Path appData = appDataKeyFile();
        if (appData != null) {
            try {
                Files.createDirectories(appData.getParent());
            } catch (IOException e) {
                return null;
            }
            return appData;
        }
        return null;
    }

    /**
     * 当前实际能读到的密钥文件（用于日志展示）。
     *
     * @return 存在的文件路径列表
     */
    public static List<Path> readableFiles() {
        List<Path> out = new ArrayList<>();
        Path program = programKeyFile();
        if (program != null && Files.isRegularFile(program)) {
            out.add(program);
        }
        Path appData = appDataKeyFile();
        if (appData != null && Files.isRegularFile(appData) && !out.contains(appData)) {
            out.add(appData);
        }
        // 旧目录也读：用户从老版本升级上来时，之前积累的密钥不能丢
        Path legacy = legacyAppDataKeyFile();
        if (legacy != null && Files.isRegularFile(legacy) && !out.contains(legacy)) {
            out.add(legacy);
        }
        return out;
    }

    /**
     * 内置密钥库是否非空（界面提示用）。
     *
     * @return 有内置密钥返回 true
     */
    public static boolean hasEmbeddedKeys() {
        return !readEmbedded().isEmpty();
    }

    /**
     * 统计密钥总数（去重后），用于日志。
     *
     * @return 密钥条数
     */
    public static int totalKeyCount() {
        int count = 0;
        for (List<byte[]> keys : load().values()) {
            count += keys.size();
        }
        return count;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 读 jar 内的内置密钥库。
     *
     * @return 账号 ID → 密钥列表（资源缺失时返回空表）
     */
    private static Map<String, List<byte[]>> readEmbedded() {
        try (InputStream stream = KeyVault.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return Map.of();
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return parse(text);
        } catch (IOException e) {
            // 内置资源读不出来不该阻断启动，退化为"没有内置密钥"
            return Map.of();
        }
    }

    /**
     * 解析一个密钥文件；文件不存在或格式异常时返回空表。
     *
     * @param file 文件路径
     * @return 账号 ID → 密钥列表
     */
    private static Map<String, List<byte[]>> parseFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 解析密钥库文本，同时支持数组形态与旧的单值形态。
     *
     * @param text 文件内容
     * @return 账号 ID → 密钥列表
     */
    private static Map<String, List<byte[]>> parse(String text) {
        Map<String, List<byte[]>> out = new LinkedHashMap<>();
        HexFormat hex = HexFormat.of();

        // 数组形态：{"<账号ID>": ["a754...", "b123..."]}
        Matcher array = ARRAY_ENTRY.matcher(text);
        while (array.find()) {
            String id = array.group(1);
            String body = array.group(2);
            Matcher value = HEX64.matcher(body);
            while (value.find()) {
                out.computeIfAbsent(id, key -> new ArrayList<>())
                        .add(hex.parseHex(value.group(1)));
            }
        }

        // 单值形态：{"<账号ID>": "a754..."}（兼容老的 keys.json）
        Matcher scalar = SCALAR_ENTRY.matcher(text);
        while (scalar.find()) {
            String id = scalar.group(1);
            byte[] key = hex.parseHex(scalar.group(2));
            List<byte[]> keys = out.computeIfAbsent(id, k -> new ArrayList<>());
            if (!contains(keys, key)) {
                keys.add(key);
            }
        }
        return out;
    }

    /**
     * 把另一个映射合并进来（同一密钥不重复加）。
     *
     * @param target 目标
     * @param extra  待合并
     */
    private static void mergeInto(Map<String, List<byte[]>> target, Map<String, List<byte[]>> extra) {
        extra.forEach((id, keys) -> {
            List<byte[]> existing = target.computeIfAbsent(id, k -> new ArrayList<>());
            for (byte[] key : keys) {
                if (!contains(existing, key)) {
                    existing.add(key);
                }
            }
        });
    }

    /**
     * 判断列表里是否已有同一把密钥。
     *
     * @param keys 已有密钥
     * @param key  待查密钥
     * @return 存在返回 true
     */
    private static boolean contains(List<byte[]> keys, byte[] key) {
        for (byte[] known : keys) {
            if (java.util.Arrays.equals(known, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 以可读的 JSON 形式写回密钥库（每行一把密钥，方便人工核对）。
     *
     * @param file 目标文件
     * @param keys 完整内容
     * @return 写入成功返回 true
     */
    private static boolean writeFile(Path file, Map<String, List<byte[]>> keys) {
        HexFormat hex = HexFormat.of();
        StringBuilder json = new StringBuilder("{\n");
        int index = 0;
        for (Map.Entry<String, List<byte[]>> entry : keys.entrySet()) {
            json.append("  \"").append(entry.getKey()).append("\": [\n");
            for (int i = 0; i < entry.getValue().size(); i++) {
                json.append("    \"").append(hex.formatHex(entry.getValue().get(i))).append('"');
                json.append(i + 1 < entry.getValue().size() ? ",\n" : "\n");
            }
            json.append("  ]");
            json.append(++index < keys.size() ? ",\n" : "\n");
        }
        json.append("}\n");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 程序目录下的密钥库路径（{@code chengyi-updatepai\keyvault.json}）。
     *
     * @return 路径
     */
    private static Path programKeyFile() {
        Path program = GameLocator.programDirectory();
        if (program == null) {
            return null;
        }
        // jpackage 下 program 指向 app 子目录，密钥库要和 exe 同级，所以取上一级
        Path parent = program.getParent();
        if (parent != null && "app".equalsIgnoreCase(program.getFileName().toString())) {
            return parent.resolve(FILE_NAME);
        }
        return program.resolve(FILE_NAME);
    }

    /**
     * 用户目录下的密钥库路径（{@code %APPDATA%\chengyi-updatepai\keyvault.json}）。
     *
     * @return 路径；取不到 APPDATA 时返回 {@code null}
     */
    private static Path appDataKeyFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }
        return Path.of(appData, APP_FOLDER, FILE_NAME);
    }

    /**
     * 旧版用户目录下的密钥库路径（{@code %APPDATA%\功夫派修改器\keyvault.json}）。
     *
     * <p>仅用于读取，不再写入——旧版本把密钥存在这个目录里，
     * 改名后如果不管它，用户升级到新版本就会突然"读不出存档"。
     *
     * @return 路径；取不到 APPDATA 时返回 {@code null}
     */
    private static Path legacyAppDataKeyFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }
        return Path.of(appData, LEGACY_APP_FOLDER, FILE_NAME);
    }

    /**
     * 只读视图，避免调用方改到内部列表。
     *
     * @param keys 原始表
     * @return 只读副本
     */
    public static Map<String, List<byte[]>> immutableCopy(Map<String, List<byte[]>> keys) {
        Map<String, List<byte[]>> copy = new LinkedHashMap<>();
        keys.forEach((id, list) -> copy.put(id, Collections.unmodifiableList(list)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 供日志使用的来源说明。
     *
     * @return 例如「内置 1 把 + 程序目录 keyvault.json 1 把」
     */
    public static String describe() {
        StringBuilder text = new StringBuilder();
        int embedded = 0;
        for (List<byte[]> keys : readEmbedded().values()) {
            embedded += keys.size();
        }
        if (embedded > 0) {
            text.append("内置 ").append(embedded).append(" 把");
        }
        for (Path file : readableFiles()) {
            if (text.length() > 0) {
                text.append(" + ");
            }
            text.append(file.getFileName()).append(" @ ").append(file.getParent());
        }
        return text.length() == 0 ? "无" : text.toString();
    }

    /**
     * 校验一个字符串是否是合法的 64 位十六进制密钥。
     *
     * @param hex 待校验文本
     * @return 合法返回 true
     */
    public static boolean isKeyHex(String hex) {
        return hex != null && hex.matches("[0-9a-fA-F]{64}");
    }
}
