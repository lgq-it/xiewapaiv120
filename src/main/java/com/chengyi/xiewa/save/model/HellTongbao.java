package com.chengyi.xiewa.save.model;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「地狱通宝」掉落账本的硬规则（游戏 1.0.7 / build 10011）。
 *
 * <p>这些规则不是估计出来的，是从游戏脚本里逐条读出来的：
 * <ul>
 *   <li>{@code globals/地狱通宝.gd}：{@code const 每日上限 := 200}、
 *       {@code const 数量表 := [5, 10, 20]}、{@code const 上线日 := "2026-10-30"}</li>
 *   <li>{@code globals/数值守卫.gd → 地狱通宝问题()}：逐日检查
 *       ①每日合计不超过 200；②每日合计必须是 5 的倍数（原文「超上限或不成数」）；
 *       ③日期必须合法、不早于上线日、不晚于「今天 + 1」；④{@code drop} 必须等于明细之和</li>
 * </ul>
 *
 * <p>所以「把差额一口气写进当天」这件事<b>根本行不通</b>：只要一天超过 200 或者不是
 * 5 的倍数，游戏载入就会判 {@code tongbao_ledger}。本类负责把一笔金额拆成合法的
 * 逐日额，并算出存档里到底还剩多少额度。
 *
 * <p>还有一条容易忽略的现实约束：修改器只做<b>原地字节补丁</b>，不能给字典新增键，
 * 所以只能往存档里<b>已经存在的日期</b>上写。玩家没在副本里掉过通宝的那天，是写不进去的。
 */
public final class HellTongbao {

    /** 每日上限，来自 {@code 地狱通宝.gd} 的 {@code 每日上限}。 */
    public static final long DAILY_CAP = 200L;

    /** 掉落金额的最小粒度，来自 {@code 数量表 = [5, 10, 20]}，所有金额都必须是 5 的倍数。 */
    public static final long UNIT = 5L;

    /** 掉落功能的上线日，早于这一天的记录游戏不认。 */
    public static final String LAUNCH_DAY = "2026-10-30";

    private HellTongbao() {
    }

    /**
     * 某天的占用情况：掉落明细加上「待入账」凭证（游戏把两者合并计算当日额度）。
     *
     * @param drops 存档里的 {@code drops}（日期 → 金额）
     * @param pend  存档里的 {@code pend}（凭证 id → {d, n}）
     * @return 日期 → 当日已占用金额
     */
    public static Map<String, Long> usage(Map<?, ?> drops, Map<?, ?> pend) {
        Map<String, Long> used = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : drops.entrySet()) {
            String day = String.valueOf(entry.getKey());
            used.merge(day, asLong(entry.getValue()), Long::sum);
        }
        for (Object raw : pend.values()) {
            if (raw instanceof Map<?, ?> voucher) {
                String day = String.valueOf(voucher.get("d"));
                used.merge(day, asLong(voucher.get("n")), Long::sum);
            }
        }
        return used;
    }

    /**
     * 某天在已有占用之上还能写多少（已按 5 向下取整）。
     *
     * @param used 当天已占用金额
     * @return 可写额度，0 表示这天已经写满
     */
    public static long freeOn(long used) {
        long left = DAILY_CAP - Math.max(0, used);
        return left <= 0 ? 0 : left / UNIT * UNIT;
    }

    /**
     * 某天是否还能被写入。
     *
     * <p>注意「不晚于今天 + 1」这一点：游戏 {@code _地狱通宝日期上界()} 取的是明天，
     * 所以明天也算合法，再往后就判「日期不合法」。
     *
     * @param day   日期串（yyyy-MM-dd）
     * @param today 今天
     * @return 可写返回 true
     */
    public static boolean dateWritable(String day, LocalDate today) {
        LocalDate date = parse(day);
        if (date == null) {
            return false;
        }
        return !date.isBefore(LocalDate.parse(LAUNCH_DAY)) && !date.isAfter(today.plusDays(1));
    }

    /**
     * 单日金额是否合规（0 &lt; 金额 ≤ 200 且为 5 的倍数）。
     *
     * @param amount 单日合计
     * @return 合规返回 true
     */
    public static boolean dayAmountValid(long amount) {
        return amount > 0 && amount <= DAILY_CAP && amount % UNIT == 0;
    }

    /**
     * 把一笔金额拆成合法的逐日写入额。
     *
     * <p>只使用存档里<b>已有</b>的日期键（原地补丁不能新增键），并从最早的日期开始填，
     * 这样看起来像是这些天陆陆续续刷出来的，今天/明天的额度留给你自己玩。
     *
     * @param amount 期望补写的总额
     * @param drops  存档里的 {@code drops}
     * @param pend   存档里的 {@code pend}
     * @param today  今天
     * @return 拆分结果
     */
    public static Split split(long amount, Map<?, ?> drops, Map<?, ?> pend, LocalDate today) {
        Map<String, Long> used = usage(drops, pend);
        long capacity = 0;
        for (Map.Entry<String, Long> entry : used.entrySet()) {
            if (dateWritable(entry.getKey(), today)) {
                capacity += freeOn(entry.getValue());
            }
        }
        long budget = Math.min(Math.max(0, amount), capacity) / UNIT * UNIT;
        Map<String, Long> byDay = new LinkedHashMap<>();
        long left = budget;
        for (Map.Entry<String, Long> entry : used.entrySet()) {
            if (left <= 0) {
                break;
            }
            if (!dateWritable(entry.getKey(), today)) {
                continue;
            }
            long take = Math.min(freeOn(entry.getValue()), left);
            if (take > 0) {
                byDay.put(entry.getKey(), take);
                left -= take;
            }
        }
        return new Split(byDay, budget, capacity);
    }

    /**
     * 把日期串解析成 {@link LocalDate}。
     *
     * @param day 日期串
     * @return 解析结果，格式不对返回 {@code null}
     */
    public static LocalDate parse(String day) {
        if (day == null || day.length() != 10) {
            return null;
        }
        try {
            return LocalDate.parse(day);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 一次拆分的结果。
     *
     * @param byDay    日期 → 本次要补写的金额（都满足单日上限与 5 的倍数）
     * @param total    合计补写金额（一定是 5 的倍数）
     * @param capacity 存档里当前可用的总额度（按 5 取整后的上限，不是本次实际写入量）
     */
    public record Split(Map<String, Long> byDay, long total, long capacity) {

        /**
         * 本次写入覆盖了几天。
         *
         * @return 天数
         */
        public int days() {
            return byDay.size();
        }
    }
}
