package com.chengyi.xiewa.save.model;

/**
 * 保存进存档的反作弊状态（{@code anti_ce} 字段）。
 *
 * <p>游戏内建了一个反作弊模块，它把状态写进存档：
 * <ul>
 *   <li>{@code tool_hits} —— 检测到作弊工具的次数（Cheat Engine 进程、调试器附加、内存注入等）</li>
 *   <li>{@code sealed} —— 是否已被标记封禁</li>
 *   <li>{@code repair_pending} —— 守卫数据被改动、等待自修复</li>
 *   <li>{@code nonce} —— 防重放随机数</li>
 * </ul>
 *
 * <p>它检测的是<b>游戏运行中的内存操作</b>，不检查磁盘上的存档文件。
 * 因此离线改档不会让 {@code tool_hits} 增加（实测长期为 0）；
 * 但在游戏运行时用内存修改工具则会。
 *
 * @param toolHits        检测到作弊工具的次数
 * @param closes          异常关闭次数
 * @param sealed          是否已被标记封禁
 * @param repairPending   是否待自修复
 * @param clientModWarned 客户端被修改的警告次数（1.0.8 新增）
 * @param tamperPending   篡改待判定标记（1.0.8 新增，非 0 说明下一次登录可能升级处理）
 * @param nonce           防重放随机数
 */
public record AntiCheatState(long toolHits, long closes, boolean sealed,
                             boolean repairPending, long clientModWarned,
                             long tamperPending, String nonce) {

    /**
     * 是否干净（没被记录过任何作弊行为）。
     *
     * @return 干净返回 true
     */
    public boolean clean() {
        return toolHits == 0 && !sealed && !repairPending
                && clientModWarned == 0 && tamperPending == 0;
    }

    /**
     * 一句话状态，用于状态栏。
     *
     * @return 例如「干净」「命中 2 次」「已标记封禁」"
     */
    public String summary() {
        if (sealed) {
            return "已标记封禁";
        }
        if (tamperPending > 0) {
            return "篡改待判定 %d 次".formatted(tamperPending);
        }
        if (toolHits > 0) {
            return "命中 %d 次".formatted(toolHits);
        }
        if (clientModWarned > 0) {
            return "客户端修改警告 %d 次".formatted(clientModWarned);
        }
        if (repairPending) {
            return "待自修复";
        }
        return "干净";
    }
}
