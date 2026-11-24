package com.chengyi.xiewa.save.keys;

import java.nio.file.Files;
import java.nio.file.Path;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 在本机直接解出存档密钥（DPAPI）。
 *
 * <p>这是首选来源：游戏每次保存都会用当前用户 + 熵重新封装密钥，
 * 而本机 DPAPI 永远能解开最新的那一份，不需要任何中间文件。
 * 只有在本机没有游戏 exe 或账号从未登录过时，才需要退回到 {@code keys.json}。
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
     * @return 可用实例；找不到游戏 exe 时返回 {@code null}
     * @throws SaveFormatException exe 版本不支持（熵表偏移越界）
     */
    public static LocalKeyProvider tryCreate() throws SaveFormatException {
        if (!DpapiUnwrapper.isSupported()) {
            return null;
        }
        Path gameExe = GameLocator.findGameExe();
        if (gameExe == null) {
            return null;
        }
        return new LocalKeyProvider(gameExe, SaveEntropy.compute(gameExe));
    }

    /**
     * 按账号目录解密钥。
     *
     * @param profileDirectory 账号目录（存档所在目录）
     * @return 32 字节密钥
     * @throws SaveFormatException 配置缺失或 DPAPI 失败
     */
    public byte[] keyForDirectory(Path profileDirectory) throws SaveFormatException {
        byte[] wrapped = ProfileKeyReader.readWrappedKey(profileDirectory);
        return DpapiUnwrapper.unprotect(wrapped, entropy);
    }

    @Override
    /**
     * 取某个账号的密钥（走本机 DPAPI 全流程）。
     *
     * @param profileId 账号 ID（用于按目录匹配 profile.cfg）
     * @return 32 字节密钥
     * @throws SaveFormatException 任一环节失败（找不到 exe / 熵 / 配置文件）
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
     * @return exe 路径
     */
    public Path gameExe() {
        return gameExe;
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
