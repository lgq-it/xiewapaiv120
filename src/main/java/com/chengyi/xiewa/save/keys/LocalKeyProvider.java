package com.chengyi.xiewa.save.keys;

import java.nio.file.Files;
import java.nio.file.Path;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 在本机直接解出存档密钥（DPAPI）。
 *
 * <p>这是首选来源：游戏每次保存都会用当前 Windows 用户重新封装密钥。
 * 1.1.1 起的 {@code XWD3} 信封使用固定熵，不依赖游戏 exe；更早的旧格式才需要
 * 从 exe 熵表推熵，因此 {@link #gameExe()} 现在可能为 {@code null}。
 */
public final class LocalKeyProvider implements SaveKeyProvider {

    private final Path gameExe;
    private final byte[] entropy;

    private LocalKeyProvider(Path gameExe, byte[] entropy) {
        this.gameExe = gameExe;
        this.entropy = entropy;
    }

    /**
     * 尝试构建本机密钥来源。
     *
     * <p>只要当前是 Windows 且 DPAPI 可用就返回实例；找不到游戏 exe 时仍可解开
     * 新版 {@code XWD3} 信封，只是旧格式会给出明确错误。
     *
     * @return 可用实例；非 Windows 返回 {@code null}
     * @throws SaveFormatException 保留给调用方统一处理（当前不会抛出）
     */
    public static LocalKeyProvider tryCreate() throws SaveFormatException {
        if (!DpapiUnwrapper.isSupported()) {
            return null;
        }
        Path gameExe = GameLocator.findGameExe();
        byte[] entropy = null;
        if (gameExe != null) {
            try {
                entropy = SaveEntropy.compute(gameExe);
            } catch (SaveFormatException ignored) {
                // 版本过新/偏移不符时仍保留实例，新版信封不需要这把熵
            }
        }
        return new LocalKeyProvider(gameExe, entropy);
    }

    /**
     * 按账号目录解密钥。
     *
     * @param profileDirectory 账号目录（存档所在目录）
     * @return 32 字节密钥
     * @throws SaveFormatException 配置缺失、格式不支持或 DPAPI 失败
     */
    public byte[] keyForDirectory(Path profileDirectory) throws SaveFormatException {
        return ProfileKeyReader.unwrapKey(profileDirectory, entropy);
    }

    @Override
    /**
     * 取某个账号的密钥（走本机 DPAPI 全流程）。
     *
     * @param profileId 账号 ID（用于按目录匹配 profile.cfg）
     * @return 32 字节密钥
     * @throws SaveFormatException 任一环节失败（找不到账号目录 / 配置 / DPAPI）
     */
    public byte[] keyFor(String profileId) throws SaveFormatException {
        Path directory = savesRoot().resolve(profileId);
        if (!Files.isDirectory(directory)) {
            throw new SaveFormatException("找不到账号目录：" + directory);
        }
        return keyForDirectory(directory);
    }

    /**
     * 游戏主程序路径，用于界面展示。
     *
     * @return exe 路径；未找到返回 {@code null}
     */
    public Path gameExe() {
        return gameExe;
    }

    /**
     * 是否拿到了旧格式所需的 exe 熵。
     *
     * @return 可用返回 true
     */
    public boolean hasLegacyEntropy() {
        return entropy != null;
    }

    /**
     * 存档根目录。
     *
     * @return {@code %APPDATA%\Ombrel\rt\saves}
     * @throws SaveFormatException 取不到 APPDATA
     */
    public static Path savesRoot() throws SaveFormatException {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            throw new SaveFormatException("取不到 APPDATA 环境变量，无法定位存档目录");
        }
        return Path.of(appData, "Ombrel", "rt", "saves");
    }
}