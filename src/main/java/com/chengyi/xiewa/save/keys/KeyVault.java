package com.chengyi.xiewa.save.keys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读密钥库。
 *
 * <p>程序本身<b>不内置、不收集、不写盘</b>任何密钥，正常使用时这个类什么也读不到，
 * 所有账号密钥都由 {@link LocalKeyProvider} 在运行时通过本机 DPAPI 现解。
 *
 * <p>保留这个类只是为了兼容极少数用户此前手动放置的 {@code keyvault.json}：
 * 如果程序目录或 {@code %APPDATA%\chengyi-updatepai} 下存在该文件，就作为兜底候选读取；
 * 没有也完全不影响正常使用。</p>
 *
 * <p>文件格式兼容两种：{@code "账号": ["hex", ...]} 与 {@code "账号": "hex"}。</p>
 */
public final class KeyVault {

    /** 密钥文件名。 */
    private static final String FILE_NAME = "keyvault.json";

    /** 用户目录下的子文件夹名。 */
    private static final String APP_FOLDER = "chengyi-updatepai";

    /** 旧版本用过的子文件夹名：只读兼容。 */
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
     * 读取用户手动放置的全部密钥文件（程序目录 + 用户目录，自动去重）。
     *
     * @return 账号 ID → 密钥列表（保持读取顺序）
     */
    public static Map<String, List<byte[]>> load() {
        Map<String, List<byte[]>> merged = new LinkedHashMap<>();
        for (Path file : readableFiles()) {
            mergeInto(merged, parseFile(file));
        }
        return merged;
    }

    /**
     * 当前实际存在的外部密钥文件。
     *
     * @return 存在的文件路径列表
     */
    public static List<Path> readableFiles() {
        List<Path> out = new ArrayList<>();
        for (Path file : new Path[]{programKeyFile(), appDataKeyFile(), legacyAppDataKeyFile()}) {
            if (file != null && Files.isRegularFile(file) && !out.contains(file)) {
                out.add(file);
            }
        }
        return out;
    }

    /**
     * 统计外部密钥总数（去重后），用于日志。
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

    /**
     * 供日志使用的来源说明。
     *
     * @return 例如「keyvault.json @ C:\...」
     */
    public static String describe() {
        StringBuilder text = new StringBuilder();
        for (Path file : readableFiles()) {
            if (text.length() > 0) {
                text.append(" + ");
            }
            text.append(file.getFileName()).append(" @ ").append(file.getParent());
        }
        return text.length() == 0 ? "无" : text.toString();
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

        Matcher array = ARRAY_ENTRY.matcher(text);
        while (array.find()) {
            String id = array.group(1);
            Matcher value = HEX64.matcher(array.group(2));
            while (value.find()) {
                out.computeIfAbsent(id, key -> new ArrayList<>())
                        .add(hex.parseHex(value.group(1)));
            }
        }

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
     * 程序目录下的密钥库路径（{@code chengyi-updatepai\keyvault.json}）。
     *
     * @return 路径
     */
    private static Path programKeyFile() {
        Path program = GameLocator.programDirectory();
        if (program == null) {
            return null;
        }
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
     * @return 路径；取不到 APPDATA 时返回 {@code null}
     */
    private static Path legacyAppDataKeyFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }
        return Path.of(appData, LEGACY_APP_FOLDER, FILE_NAME);
    }
}