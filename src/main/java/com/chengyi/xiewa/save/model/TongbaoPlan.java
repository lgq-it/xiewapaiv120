package com.chengyi.xiewa.save.model;

/**
 * 一次通宝修改的方案说明（同时用于界面提示与日志）。
 *
 * @param total       要写入的累计签到天数
 * @param cycleDay    要写入的轮次
 * @param earned      该天数对应的签到所得（= 平衡点）
 * @param spent       要写入的累计花费（用于吃掉差额）
 * @param balance     实际写入的通宝余额
 * @param lastDate    要写入的上次签到日期（昨天）
 * @param capToday    今天的签到天数上限
 * @param capped      是否因上限而没达到用户期望值
 * @param requested   用户输入的期望值
 * @param dateSkipped 是否因为没有原日期字段而跳过了日期改写
 * @param forced      是否是「强制指定天数」模式（此时不保证游戏内还能签到）
 * @param dropAdded   本次写进「副本掉落」账本的金额（0 表示走的是签到天数那条路）
 * @param spentAdded  本次额外记进「累计花费」的金额（减钱通道用；0 表示没走这条路）
 * @param dropDays    本次掉落金额摊到了几天（游戏每日掉落上限 200、且必须是 5 的倍数）
 */
public record TongbaoPlan(int total, int cycleDay, long earned, long spent, long balance,
                          String lastDate, int capToday, boolean capped, long requested,
                          boolean dateSkipped, boolean forced, long dropAdded, long spentAdded,
                          int dropDays) {

    /**
     * 复制一份方案，只把「跳过日期改写」标记换掉。
     *
     * @param skipped 新标记
     * @return 新方案
     */
    public TongbaoPlan withDateSkipped(boolean skipped) {
        return new TongbaoPlan(total, cycleDay, earned, spent, balance, lastDate,
                capToday, capped, requested, skipped, forced, dropAdded, spentAdded, dropDays);
    }

    /**
     * 复制一份方案，把写入存档时实际算出的花费与余额换掉。
     *
     * <p>方案阶段算出的花费只考虑了「签到所得」，而实际存档里还有副本掉落等入账项，
     * 所以真正写档前要按总入账守恒重算一遍（见 {@code SaveService.applyPlan}）。
     *
     * @param newSpent  实际写入的累计花费
     * @param newBalance 实际写入的余额
     * @param newCapped 是否因为入账上限而没达到期望值
     * @return 新方案
     */
    public TongbaoPlan withLedger(long newSpent, long newBalance, boolean newCapped) {
        return new TongbaoPlan(total, cycleDay, earned, newSpent, newBalance, lastDate,
                capToday, newCapped, requested, dateSkipped, forced, dropAdded, spentAdded, dropDays);
    }

    /**
     * 生成一句人话说明。
     *
     * @return 说明文字
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("实际到账 ").append(balance).append(" 通宝（签到 ").append(total)
                .append(" 天，账本花费 ").append(spent).append("）");
        if (capped) {
            text.append("\n未能达到 ").append(requested).append("：");
            if (forced) {
                // 天数这条路的真上限：今天能签到的天数（游戏签到会把日期改成今天，
                // 写未来日期只能骗过载入核对，一点签到就报 tongbao_ledger）
                text.append("累计天数想写超过 ").append(capToday).append(" 天，")
                        .append("会让游戏内一点签到就报 tongbao_ledger（游戏会把「上次签到日期」改成今天，")
                        .append("上限当场塌回今天可达的天数）。已按 ").append(total).append(" 天写入。");
            } else {
                // 掉落通道的封顶：不是天数不够，而是「合法入账空间」用完了
                text.append("合法入账空间已用尽 —— 余额不能超过「签到所得 + 地狱通宝掉落」，")
                        .append("\n而掉落每天最多 ").append(HellTongbao.DAILY_CAP)
                        .append("、必须是 ").append(HellTongbao.UNIT)
                        .append(" 的倍数，且只能写进存档里已有的掉落日期。");
            }
        }
        if (dropAdded > 0) {
            // 掉落通道：签到天数、日期、花费一个都没动，只把差额记成地狱通宝掉落。
            // 游戏对掉落有硬限制（每日 200、5 的倍数），所以必须按天摊开写。
            text.append("\n差额 ").append(dropAdded).append(" 通宝记入「地狱通宝」掉落账本");
            if (dropDays > 0) {
                text.append("（按 ").append(dropDays).append(" 天分摊，游戏每日上限 ")
                        .append(HellTongbao.DAILY_CAP).append("、且必须是 ")
                        .append(HellTongbao.UNIT).append(" 的倍数）");
            }
            text.append("，\n签到天数与日期均未改动（商城解锁档位保持不变）。");
            return text.toString();
        }
        if (spentAdded > 0) {
            // 花费通道：只让「累计花费」吸收差额，天数、日期、掉落全都不动。
            // 加钱时花费变少（回收以前记的花费），减钱时花费变多，两种都靠这一句说明。
            text.append("\n差额 ").append(spentAdded).append(" 通宝由「累计花费」吸收（现为 ")
                    .append(spent).append("），签到天数、日期与掉落明细均未改动。");
            return text.toString();
        }
        if (dateSkipped) {
            text.append("\n注意：这份存档没有「上次签到日期」（从未签到过），日期字段未改写。")
                    .append("进游戏手动签到一次即可恢复正常。");
        } else {
            text.append("\n签到日期已设为昨天，进游戏可以直接签到。");
        }
        return text.toString();
    }
}
