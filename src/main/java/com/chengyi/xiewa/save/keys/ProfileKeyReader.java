package com.chengyi.xiewa.save.keys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 读取 {@code profile.cfg} 中被 DPAPI 保护的存档密钥。
 *
 * <p>文件是极简的 {@code key="value"} 文本，密钥那一行长这样：
 * <pre>wrapped_key="AQAAANCMnd8BFdERjHoAwE/Cl..."</pre>
 * 值本身是 base64 编码的 DPAPI blob，需要解 base64 再交给 DPAPI 解封。
 */
public final class ProfileKeyReader {

    /** 配置文件名。 */
    public static final String PROFILE_FILE = "profile.cfg";

    private static final Pattern WRAPPED_KEY =
            Pattern.compile("wrapped_key\\s*=\\s*\"([^\"]+)\"");

    private ProfileKeyReader() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 判断某个账号目录下是否有可用于解析密钥的 profile.cfg。
     *
     * @param profileDirectory 账号目录（例如 {@code .../saves/205273411}）
     * @return 存在返回 true
     */
    public static boolean hasProfile(Path profileDirectory) {
        return profileDirectory != null
                && Files.isRegularFile(profileDirectory.resolve(PROFILE_FILE));
    }

    /**
     * 取出被保护的密钥字节（base64 解码后）。
     *
     * @param profileDirectory 账号目录
     * @return DPAPI blob
     * @throws SaveFormatException 文件缺失、无 wrapped_key 字段，或 base64 非法
     */
    public static byte[] readWrappedKey(Path profileDirectory) throws SaveFormatException {
        Path file = profileDirectory.resolve(PROFILE_FILE);
        if (!Files.isRegularFile(file)) {
            throw new SaveFormatException("账号目录下没有 " + PROFILE_FILE + "：" + profileDirectory);
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SaveFormatException("读取 " + PROFILE_FILE + " 失败：" + file, e);
        }
        Matcher matcher = WRAPPED_KEY.matcher(text);
        if (!matcher.find()) {
            throw new SaveFormatException(PROFILE_FILE + " 中没有 wrapped_key 字段（账号可能从未登录过）：" + file);
        }
        try {
            return Base64.getDecoder().decode(matcher.group(1).trim());
        } catch (IllegalArgumentException e) {
            throw new SaveFormatException("wrapped_key 不是合法的 base64：" + file, e);
        }
    }
}
