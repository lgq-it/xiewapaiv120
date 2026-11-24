package com.chengyi.xiewa.save.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 掉落保底规则表：把存档里 {@code drop_pity} / {@code jewelry_pity} 的计数键
 * 翻译成「物品名 + 难度 + 保底次数」。
 *
 * <p>数据来源是游戏资源包里的 {@code resources/掉落/保底规则.json}
 * （11 条关卡级规则 + 8 条 item_rules 级规则）与三组首饰掉落组
 * （{@code resources/掉落/首饰/44|45|46-精英.tres}，各自 20 次保底）。
 * 提取后压成 TSV 随程序打包，不引入 JSON 库。
 *
 * <p><b>只收录规则明确的键。</b>存档里还可能有 {@code guardian:<关卡名>:<物品ID>}
 * 与 {@code monster:<物品ID>} 两类键，它们的保底次数由关卡脚本在运行时决定、
 * 资源包内查不到上限，因此不在本表内 —— 这类计数一律不动。
 */
public final class PityCatalog {

    /** 存档字段：BOSS 材料保底（键形如 {@code 926:1500350} 或 {@code pity:ice_soul:英雄}）。 */
    public static final String SECTION_DROP = "drop_pity";

    /** 存档字段：首饰保底（键就是物品 ID）。 */
    public static final String SECTION_JEWELRY = "jewelry_pity";

    /** 随程序打包的规则表。 */
    private static final String RESOURCE = "/catalog/pity_rules.tsv";

    /**
     * 一条保底规则。
     *
     * @param section  所属存档字段（{@link #SECTION_DROP} / {@link #SECTION_JEWELRY}）
     * @param key      存档字典里的键
     * @param itemId   物品 ID
     * @param name     物品名
     * @param scope    作用范围（难度名，或「关卡 926」）
     * @param maxRolls 保底次数：连续这么多次没出，下一次必出
     * @param rate     单次掉率（百分比，仅用于界面展示）
     */
    public record Rule(String section, String key, int itemId, String name,
                       String scope, int maxRolls, double rate) {
    }

    private final List<Rule> rules;

    private PityCatalog(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * 空表（资源缺失时的兜底，界面据此显示"没有可用的保底规则"）。
     *
     * @return 不含任何规则的目录
     */
    public static PityCatalog empty() {
        return new PityCatalog(List.of());
    }

    /**
     * 载入随程序打包的规则表。
     *
     * @return 规则表
     * @throws IOException 资源缺失或读失败
     */
    public static PityCatalog loadDefault() throws IOException {
        try (InputStream in = PityCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("程序内缺少保底规则表：" + RESOURCE);
            }
            return new PityCatalog(parse(in));
        }
    }

    private static List<Rule> parse(InputStream in) throws IOException {
        List<Rule> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 7) {
                    continue;
                }
                try {
                    out.add(new Rule(parts[0], parts[1], Integer.parseInt(parts[2].trim()),
                            parts[3], parts[4], Integer.parseInt(parts[5].trim()),
                            Double.parseDouble(parts[6].trim())));
                } catch (NumberFormatException ignored) {
                    // 单行格式异常不影响整表
                }
            }
        }
        return out;
    }

    /** @return 全部规则 */
    public List<Rule> rules() {
        return rules;
    }

    /**
     * 按存档字段取规则。
     *
     * @param section {@link #SECTION_DROP} 或 {@link #SECTION_JEWELRY}
     * @return 该字段下的规则（顺序与资源文件一致）
     */
    public List<Rule> rules(String section) {
        List<Rule> out = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.section().equals(section)) {
                out.add(rule);
            }
        }
        return out;
    }

    /** @return 规则条数 */
    public int size() {
        return rules.size();
    }
}
