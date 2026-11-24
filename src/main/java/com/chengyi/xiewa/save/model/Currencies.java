package com.chengyi.xiewa.save.model;

/**
 * 账户级数值快照（货币 + 签到 + 账本）。
 *
 * <p>账本字段在游戏 1.0.7（build 10011）里从 4 项扩到 8 项，新增
 * {@code god / drop / drops / pend}。其中：
 * <ul>
 *   <li>{@code drop} —— 副本掉落的通宝累计额</li>
 *   <li>{@code drops} —— 按日期分组的掉落明细，各项之和必须等于 {@code drop}</li>
 *   <li>{@code pend} —— 待入账（尚未结算的掉落）</li>
 *   <li>{@code god} —— 与 god_mode 相关的额外入账，正常为 0</li>
 * </ul>
 *
 * <p>实测等式（2026-10-30，drop=20 的样本）：
 * <pre>余额 = 签到所得 + 管理员 + drop − 花费</pre>
 * 所以改钱时<b>不能</b>只按「签到所得 − 花费」算差额，否则 drop 有多少就少算多少。
 *
 * @param tongbao       通宝余额
 * @param demonPoints   魔点
 * @param spent         账本累计花费
 * @param admin         管理员入账（正常为 0）
 * @param legacy        旧件数（正常为 0）
 * @param buySum        购买记录金额合计
 * @param signinTotal   累计签到天数
 * @param signinCycleDay 签到轮次
 * @param lastSigninDate 上次签到日期
 * @param drop          副本掉落通宝累计（1.0.7 新增）
 * @param god           god_mode 相关入账（1.0.7 新增，正常为 0）
 * @param pend          待入账合计（1.0.7 新增）
 * @param dropSum       drops 明细各日之和（用于校验与 drop 是否一致）
 */
public record Currencies(long tongbao, long demonPoints, long spent, long admin, long legacy,
                         long buySum, int signinTotal, int signinCycleDay, String lastSigninDate,
                         long drop, long god, long pend, long dropSum) {

    /**
     * 当前总入账。
     *
     * <p>由「余额 = 总入账 − 花费」反推而来：{@code 总入账 = 余额 + 花费}。
     * 这个关系由游戏自己维持，所以不需要知道入账具体由哪几个字段组成，
     * 改钱时用它做基准最稳。
     *
     * @return 总入账
     */
    public long totalIncome() {
        return tongbao + spent;
    }

    /**
     * 存档里是否已经出现 1.0.7 新增的记账数据。
     *
     * @return 任一新字段非零返回 true
     */
    public boolean hasNewLedgerFields() {
        return drop != 0 || dropSum != 0 || pend != 0 || god != 0;
    }

    /**
     * {@code drop} 与逐日明细之和是否一致。
     *
     * @return 一致返回 true
     */
    public boolean dropDetailConsistent() {
        return drop == dropSum;
    }

    /**
     * 账本是否各项校验全过（过不了就说明载入或签到会报错）。
     *
     * @return 全部满足返回 true
     */
    public boolean ledgerHealthy() {
        return SigninLedger.isConsistent(tongbao, signinTotal, signinCycleDay,
                lastSigninDate, spent, admin, buySum, drop, god);
    }

    /**
     * 今天还能再签到吗（上次签到不是今天即可）。
     *
     * @param today 今天的日期字符串
     * @return 可以签到返回 true
     */
    public boolean canSigninToday(String today) {
        return !today.equals(lastSigninDate);
    }

    /**
     * 一行账本摘要，用于日志与状态栏。
     *
     * @return 例如「花费 261 · 掉落 20 · 管理员 0 · 购买记录 55 条」
     */
    public String ledgerSummary() {
        StringBuilder text = new StringBuilder();
        text.append("花费 ").append(spent).append(" · 管理员 ").append(admin);
        if (hasNewLedgerFields()) {
            text.append(" · 掉落 ").append(drop);
            if (pend != 0) {
                text.append(" · 待入账 ").append(pend);
            }
            if (god != 0) {
                text.append(" · god ").append(god);
            }
        }
        text.append(" · 购买金额合计 ").append(buySum);
        return text.toString();
    }
}
