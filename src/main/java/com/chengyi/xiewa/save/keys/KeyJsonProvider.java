package com.chengyi.xiewa.save.keys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 从 {@code keys.json} 读取存档密钥。
 *
 * <p>文件由「取密钥工具」生成，格式为扁平的 {@code {"账号ID": "hex密钥"}}，
 * 例如 {@code {"205273411": "a75486..."}}。结构简单，故不引入 JSON 库，
 * 直接用正则提取键值对——若将来格式变复杂，再换成正式解析器。
 */
public final class KeyJsonProvider implements SaveKeyProvider {

    /** 匹配 "键": "值" 形式的字符串对。 */
    private static final Pattern PAIR =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private final Map<String, byte[]> keys = new LinkedHashMap<>();
    private final Path source;

    private KeyJsonProvider(Path source) {
        this.source = source;
    }

    /**
     * 从指定文件加载。
     *
     * @param jsonFile keys.json 路径
     * @return 密钥提供者
     * @throws SaveFormatException 文件不存在、不可读，或其中没有任何合法密钥
     */
    public static KeyJsonProvider load(Path jsonFile) throws SaveFormatException {
        if (!Files.isRegularFile(jsonFile)) {
            throw new SaveFormatException("密钥文件不存在：" + jsonFile.toAbsolutePath());
        }
        String text;
        try {
            text = Files.readString(jsonFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SaveFormatException("读取密钥文件失败：" + jsonFile.toAbsolutePath(), e);
        }

        KeyJsonProvider provider = new KeyJsonProvider(jsonFile);
        provider.parse(text);
        if (provider.keys.isEmpty()) {
            throw new SaveFormatException("密钥文件中没有任何合法密钥：" + jsonFile.toAbsolutePath());
        }
        return provider;
    }

    /**
     * 在候选路径中依次查找 keys.json（工作目录、程序所在目录）。
     *
     * @param candidates 候选文件路径，按优先级排列
     * @return 命中的第一个可用提供者
     * @throws SaveFormatException 全部候选都不存在
     */
    public static KeyJsonProvider loadFirstAvailable(Path... candidates) throws SaveFormatException {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return load(candidate);
            }
        }
        throw new SaveFormatException("未找到 keys.json，请先用「取密钥工具」在本机生成");
    }

    /**
     * 取某个账号的密钥。
     *
     * @param profileId 账号 ID（keys.json 的键）
     * @return 32 字节密钥
     * @throws SaveFormatException 文件不存在或没有这个账号的条目
     */
    @Override
    public byte[] keyFor(String profileId) throws SaveFormatException {
        byte[] key = keys.get(profileId);
        if (key == null) {
            throw new SaveFormatException(
                    "密钥文件中没有账号 %s 的密钥（现有账号：%s）。请在当前机器上重新运行取密钥工具。"
                            .formatted(profileId, String.join(", ", keys.keySet())));
        }
        return key.clone();
    }

    /**
     * 列举文件中记录的账号 ID。
     *
     * @return 账号 ID 集合（保持文件中的顺序）
     */
    public java.util.Set<String> profileIds() {
        return java.util.Collections.unmodifiableSet(keys.keySet());
    }

    /**
     * 全部密钥（账号 ID → 32 字节密钥，保持文件中的顺序）。
     *
     * <p>用于"哪个密钥能解开就用哪个"的兜底：存档从别的机器拷过来时，
     * 目录名未必等于 keys.json 里的账号 ID，逐个试比直接报错友好得多。
     *
     * @return 只读映射（值已复制，调用方改动不影响本对象）
     */
    public Map<String, byte[]> allKeys() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        keys.forEach((id, key) -> copy.put(id, key.clone()));
        return java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * 密钥来源描述，用于日志。
     *
     * @return 文件绝对路径
     */
    public String describeSource() {
        return source.toAbsolutePath().toString();
    }

    private void parse(String text) throws SaveFormatException {
        Matcher matcher = PAIR.matcher(text);
        while (matcher.find()) {
            String id = matcher.group(1);
            String hex = matcher.group(2);
            if (hex.length() != 64) {
                throw new SaveFormatException(
                        "账号 %s 的密钥长度异常：应为 64 个十六进制字符，实际 %d 个"
                                .formatted(id, hex.length()));
            }
            keys.put(id, HexFormat.of().parseHex(hex));
        }
    }
}
