package com.chengyi.xiewa.save.keys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 读取 {@code profile.cfg} 中被 DPAPI 保护的存档密钥。
 *
 * <p>文件是极简的 {@code key="value"} 文本，密钥那一行长这样：
 * <pre>wrapped_key="AQAAANCMnd8BFdERjHoAwE/Cl..."</pre>
 * 游戏 1.1.1 起值变成 {@code XWD3} 信封，内部是两段固定熵保护的 DPAPI blob；
 * 更早的版本则直接是一个 DPAPI blob。两种形态都在这里兼容。
 */
public final class ProfileKeyReader {

    /** 配置文件名。 */
    public static final String PROFILE_FILE = "profile.cfg";

    /** 新版 wrapped_key 的信封魔数。 */
    private static final String ENVELOPE_MAGIC = "XWD3";

    /** 信封头部长度：4 字节魔数 + 4 字节第一段 blob 长度。 */
    private static final int ENVELOPE_HEADER = 8;

    /**
     * 新版信封使用的第一把固定熵。
     *
     * <p>游戏从 1.1.1 起把 wrapped_key 改成 {@code XWD3 + u32(L) + blob1 + blob2}，
     * 两段 blob 分别用这两把固定熵保护，不再依赖 exe 里的熵表。
     */
    private static final byte[] ENVELOPE_ENTROPY_A =
            HexFormat.of().parseHex("2c740f131381cbfbafbe8306c6ca29353d209d97269deff43b08d6716543b325");

    /** 新版信封使用的第二把固定熵。 */
    private static final byte[] ENVELOPE_ENTROPY_B =
            HexFormat.of().parseHex("47b1babf88713460c654da0af8bd1a484b435641bf8b99278625b4acd20998bc");

    /** AES-256 密钥长度。 */
    private static final int KEY_LENGTH = 32;

    private static final Pattern WRAPPED_KEY =
            Pattern.compile("wrapped_key\\s*=\\s*\"([^\"]+)\"");

    private ProfileKeyReader() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 判断某个账号目录下是否有可用于解析密钥的 profile.cfg。
     *
     * @param profileDirectory 账号目录（例如 {@code .../saves/<账号ID>}）
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
     * @return DPAPI blob，或完整的 {@code XWD3} 信封
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

    /**
     * 解出某个账号的 32 字节存档密钥。
     *
     * <p>兼容两种格式：
     * <ul>
     *   <li>新版 {@code XWD3} 信封：两段 DPAPI blob 各用固定熵保护，无需游戏 exe；</li>
     *   <li>旧版单个 DPAPI blob：使用调用方从游戏 exe 推出的熵。</li>
     * </ul>
     *
     * @param profileDirectory 账号目录
     * @param legacyEntropy    旧格式所需的 exe 熵；新格式可传 {@code null}
     * @return 32 字节密钥
     * @throws SaveFormatException 文件缺失、格式异常，或 DPAPI 解封失败
     */
    public static byte[] unwrapKey(Path profileDirectory, byte[] legacyEntropy)
            throws SaveFormatException {
        byte[] wrapped = readWrappedKey(profileDirectory);
        if (isEnvelope(wrapped)) {
            return unwrapEnvelope(profileDirectory, wrapped);
        }
        if (legacyEntropy == null) {
            throw new SaveFormatException(
                    "账号 %s 的 %s 是旧格式，需要找到游戏 XiewaPai.exe 才能解出密钥。"
                            .formatted(profileDirectory.getFileName(), PROFILE_FILE));
        }
        return DpapiUnwrapper.unprotect(wrapped, legacyEntropy);
    }

    /**
     * 判断 wrapped_key 是否是 {@code XWD3} 信封。
     *
     * @param wrapped 解码后的字节
     * @return 是信封返回 true
     */
    private static boolean isEnvelope(byte[] wrapped) {
        if (wrapped == null || wrapped.length < ENVELOPE_HEADER) {
            return false;
        }
        for (int i = 0; i < ENVELOPE_MAGIC.length(); i++) {
            if (wrapped[i] != (byte) ENVELOPE_MAGIC.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解开 {@code XWD3} 信封。
     *
     * <p>格式：{@code "XWD3" + u32(第一段长度) + 第一段 DPAPI blob + 第二段 DPAPI blob}。
     * 两段是同一把密钥的两种保护副本，正常任一段都能解出；这里按固定熵配对，
     * 再交叉尝试一次，避免个别版本调换顺序。
     *
     * @param profileDirectory 账号目录（仅用于报错）
     * @param wrapped          完整信封
     * @return 32 字节密钥
     * @throws SaveFormatException 信封结构异常或两段都解不开
     */
    private static byte[] unwrapEnvelope(Path profileDirectory, byte[] wrapped)
            throws SaveFormatException {
        int firstLength = (wrapped[4] & 0xFF)
                | ((wrapped[5] & 0xFF) << 8)
                | ((wrapped[6] & 0xFF) << 16)
                | ((wrapped[7] & 0xFF) << 24);
        if (firstLength <= 0 || ENVELOPE_HEADER + firstLength > wrapped.length) {
            throw new SaveFormatException(
                    "账号 %s 的 %s 信封长度异常：%d".formatted(
                            profileDirectory.getFileName(), PROFILE_FILE, firstLength));
        }

        byte[] first = Arrays.copyOfRange(wrapped, ENVELOPE_HEADER, ENVELOPE_HEADER + firstLength);
        byte[] second = Arrays.copyOfRange(wrapped, ENVELOPE_HEADER + firstLength, wrapped.length);

        byte[][] blobs = second.length == 0 ? new byte[][]{first} : new byte[][]{first, second};
        byte[][] entropies = {ENVELOPE_ENTROPY_A, ENVELOPE_ENTROPY_B};
        SaveFormatException last = null;
        for (byte[] blob : blobs) {
            for (byte[] entropy : entropies) {
                try {
                    byte[] key = DpapiUnwrapper.unprotect(blob, entropy);
                    if (key.length == KEY_LENGTH) {
                        return key;
                    }
                } catch (SaveFormatException e) {
                    last = e;
                }
            }
        }
        throw new SaveFormatException(
                "账号 %s 的 %s 解封失败（XWD3 信封）。请确认是以生成该存档的同一个 Windows 用户运行；"
                        .formatted(profileDirectory.getFileName(), PROFILE_FILE)
                        + (last == null ? "" : "底层原因：" + last.getMessage()), last);
    }
}