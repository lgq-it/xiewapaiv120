package com.chengyi.xiewa.save.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 签到与通宝账本规则（对照游戏脚本 {@code 数值守卫} 与 {@code 签到}）。
 *
 * <p>通宝是整个存档里<b>唯一带账本校验</b>的货币，规则有四条：
 * <ol>
 *   <li>余额 == 签到所得(累计天数) + 管理员入账 − 累计花费</li>
 *   <li>购买记录金额合计 ≤ 累计花费</li>
 *   <li>轮次 == (累计天数 − 1) % 28 + 1</li>
 *   <li>累计天数 ≤ 上限，上限 =（上次签到日期 − 2026-10-18）的天数 + 1</li>
 * </ol>
 *
 * <p>第四条是坑：游戏点签到时会把「上次签到日期」改成今天，上限当场按今天重算。
 * 若之前为了堆余额把日期写到未来，一签到上限就塌陷，存档会被拒（报 {@code tongbao_ledger}）。
 * 所以安全的做法是：累计天数取「今天可达上限 − 1」、日期设为昨天，把差额压进
 * {@code spent}（游戏只校验等式，不校验 spent 取值），改完当天即可正常签到。
 */
public final class SigninLedger {

    /** 每日基础通宝。 */
    public static final int PER_DAY = 100;

    /** 一轮的天数。 */
    public static final int CYCLE_DAYS = 28;

    /** 累计奖励：这些天额外加钱。 */
    private static final Map<Integer, Integer> BONUS = Map.of(7, 200, 14, 200, 21, 200, 28, 200);

    /** 签到系统的起点日期，早于它不存在"签到天数"。 */
    public static final LocalDate FLOOR_DATE = LocalDate.of(2026, 10, 18);

    /**
     * 允许写入的最大累计天数。
     *
     * <p>累计天数会换算成「上次签到日期」（起点 + 天数 − 1），天数写得太大会把日期
     * 推到离谱的年份，游戏未必接受。10 年（3650 天）对应的签到所得约 47 万通宝，
     * 远超正常需求，作为安全护栏足够。
     */
    public static final int MAX_DAYS = 3650;

    private SigninLedger() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 累计签到 {@code total} 天所能获得的通宝总额。
     *
     * @param total 累计天数（负值按 0 处理）
     * @return 通宝数
     */
    public static long earned(int total) {
        int days = Math.max(0, total);
        long perCycle = BONUS.values().stream().mapToLong(Integer::longValue).sum();
        int remainder = days % CYCLE_DAYS;
        long remainderBonus = BONUS.entrySet().stream()
                .filter(e -> remainder >= e.getKey())
                .mapToLong(Map.Entry::getValue)
                .sum();
        return (long) days * PER_DAY + (days / CYCLE_DAYS) * perCycle + remainderBonus;
    }

    /**
     * 某个「上次签到日期」允许的最大累计天数。
     *
     * @param lastDate 上次签到日期（{@code yyyy-MM-dd}），空或非法返回 0
     * @return 上限
     */
    public static int capDays(String lastDate) {
        if (lastDate == null || lastDate.isBlank()) {
            return 0;
        }
        try {
            LocalDate date = LocalDate.parse(lastDate.trim());
            return (int) Math.max(0, ChronoUnit.DAYS.between(FLOOR_DATE, date) + 1);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * 求「签到所得 ≥ 目标余额」所需的最小累计天数。
     *
     * <p>账本规则之一是「余额 = 签到所得 + 管理员 − 花费」，且「购买记录金额 ≤ 花费」。
     * 购买记录金额已被清零，所以花费不能为负 —— 这等于说<b>余额不可能超过签到所得</b>。
     * 想要更多通宝，唯一合法的路就是抬高累计天数。
     *
     * @param target  期望余额
     * @param atLeast 天数下限（通常传存档当前天数，保证只增不减，避免把商城解锁档位打回去）
     * @return 天数（不超过 {@link #MAX_DAYS}）
     */
    public static int minDaysFor(long target, int atLeast) {
        int day = Math.max(1, atLeast);
        long want = Math.max(0, target);
        while (day < MAX_DAYS && earned(day) < want) {
            day++;
        }
        return day;
    }

    /**
     * 计算一份「改完还能正常签到」的通宝方案。
     *
     * @param target 期望余额
     * @param today  当前日期
     * @return 方案
     */
    public static TongbaoPlan plan(long target, LocalDate today) {
        int capToday = capDays(today.toString());
        int total = Math.max(0, capToday - 1);
        int cycleDay = total == 0 ? 1 : (total - 1) % CYCLE_DAYS + 1;
        long earnedTotal = earned(total);
        long want = Math.max(0, target);
        long balance = Math.min(want, earnedTotal);
        long spent = earnedTotal - balance;
        LocalDate lastDate = today.minusDays(1);
        return new TongbaoPlan(total, cycleDay, earnedTotal, spent, balance,
                lastDate.toString(), capToday, want > earnedTotal, want, false, false, 0L, 0L, 0);
    }

    /**
     * 今天最多能有多少累计天数（游戏点签到时的硬上限）。
     *
     * <p>等于 {@code capDays(今天)}。游戏每次签到都会把「上次签到日期」改成当天，
     * 于是上限当场按今天重算 —— 所以这个数字就是「签到这条路今天最多能撑到几天」。
     *
     * @param today 今天
     * @return 上限天数
     */
    public static int capToday(LocalDate today) {
        return capDays(today.toString());
    }

    /**
     * 当前存档的天数会不会让「游戏内签到」被拒。
     *
     * <p>游戏 {@code 数值守卫.核账()} 只比较 {@code total ≤ 上限(上次签到日期)}，
     * 所以把日期写到未来就能让超量的天数「看起来合法」—— 存档能载入、能买东西，
     * 但玩家一点签到，日期被改成今天，上限立刻塌回 {@link #capToday(LocalDate)}，
     * 于是报 {@code tongbao_ledger}（游戏存档层原样返回这个 key）。
     *
     * @param total    累计天数
     * @param lastDate 存档里的上次签到日期
     * @param today    今天
     * @return 超出的天数（0 表示签到没问题）
     */
    public static int overflowDays(int total, String lastDate, LocalDate today) {
        if (total <= 0) {
            return 0;
        }
        // 取「当前日期撑住的上限」与「今天可签到的上限」里更严的那个
        int capNow = Math.min(capDays(lastDate), capToday(today));
        return Math.max(0, total - capNow);
    }

    /**
     * 计算一份「指定累计天数」的通宝方案。
     *
     * <p>天数超过今天可达的上限时会被<b>压到上限</b>（并把 {@code capped} 标成 true）：
     * 写未来日期虽然能骗过载入核对，但玩家一点签到就报 {@code tongbao_ledger}，
     * 这是坑，不能默认给用户埋。
     *
     * @param days   期望累计天数（小于 1 按 1 处理）
     * @param target 期望余额（超出该天数签到所得的部分会被截断）
     * @param today  今天
     * @return 方案
     */
    public static TongbaoPlan planDays(int days, long target, LocalDate today) {
        int cap = Math.max(1, capToday(today));
        int wanted = Math.max(1, days);
        boolean beyond = wanted > cap;
        int day = Math.min(wanted, cap);
        int cycleDay = (day - 1) % CYCLE_DAYS + 1;
        long earnedTotal = earned(day);
        long want = Math.max(0, target);
        long balance = Math.min(want, earnedTotal);
        long spent = earnedTotal - balance;
        LocalDate lastDate = FLOOR_DATE.plusDays(day - 1L);
        return new TongbaoPlan(day, cycleDay, earnedTotal, spent, balance,
                lastDate.toString(), cap, beyond || want > earnedTotal, want, false, beyond, 0L, 0L, 0);
    }

    /**
     * 校验一份账本是否各项全过（界面用于给出「签到可用」的结论）。
     *
     * <p>1.0.7 起等式多了「副本掉落」一项（{@code drop}）以及 {@code god}，
     * 实测样本：{@code 9959 = 10200 + 0 + 20 − 261}。少算这一项会把账本判成不平，
     * 所以这里必须一起带上。
     *
     * @param balance       余额
     * @param total         累计天数
     * @param cycleDay      轮次
     * @param lastDate      上次签到日期
     * @param spent         累计花费
     * @param admin         管理员入账
     * @param buySum        购买记录金额合计
     * @param drop          副本掉落累计（1.0.7 新增）
     * @param god           god_mode 相关入账（1.0.7 新增）
     * @return 各项是否全部满足
     */
    public static boolean isConsistent(long balance, int total, int cycleDay, String lastDate,
                                       long spent, long admin, long buySum, long drop, long god) {
        boolean equation = balance == earned(total) + admin + drop + god - spent;
        boolean buys = buySum <= spent;
        boolean cycle = total == 0 || cycleDay == (total - 1) % CYCLE_DAYS + 1;
        boolean cap = total <= capDays(lastDate);
        return equation && buys && cycle && cap;
    }
}
