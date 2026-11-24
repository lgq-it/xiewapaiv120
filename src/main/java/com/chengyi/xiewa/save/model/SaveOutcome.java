package com.chengyi.xiewa.save.model;

import java.nio.file.Path;

/**
 * 一次保存的结果。
 *
 * @param generation       写入后的代数
 * @param backup           原档备份路径
 * @param verified         重新读盘复算签名是否通过
 * @param generationBumped 代数是否自增。1.0.9（build 10017）起为 false：
 *                         游戏收紧了水位校验，代数前进会被整份回滚，
 *                         修改器改为保持代数不变、由游戏走「内容与水位记录不符」
 *                         的回滚窗口（R3f）接受。该通道有 24 小时冷却。
 */
public record SaveOutcome(long generation, Path backup, boolean verified, boolean generationBumped) {
}
