package com.chengyi.xiewa.save.keys;

import com.chengyi.xiewa.save.core.SaveFormatException;

/**
 * 存档密钥来源。
 *
 * <p>密钥与「本机 Windows 用户」绑定（DPAPI 保护），因此改别的机器上的存档前，
 * 必须先在目标机器上取一次密钥。不同来源对应不同实现。
 */
@FunctionalInterface
public interface SaveKeyProvider {

    /**
     * 取得指定账号的存档密钥。
     *
     * @param profileId 账号 ID（存档目录名，例如 {@code <账号ID>}）
     * @return 32 字节密钥
     * @throws SaveFormatException 找不到该账号的密钥，或密钥格式非法
     */
    byte[] keyFor(String profileId) throws SaveFormatException;
}
