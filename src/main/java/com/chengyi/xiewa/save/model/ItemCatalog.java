package com.chengyi.xiewa.save.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物品目录：ID → 名称、ID → 所在背包分区。
 *
 * <p>数据是从游戏资源包（{@code XiewaPai.pck}）里提取后随程序打包的 JSON：
 * {@code item_names.json}（3756 条名称）、{@code item_sections.json}（895 条：id → items/materials）、
 * {@code equipment_ids.json}（2401 个当前版本可获得的装备 ID，已剔除官方下架与内容专用件）
 * 与 {@code equipment_meta.json}（2401 条：id → [部位, 质量]，用于判定装备能否强化）。
 * 结构极其扁平（就是字符串映射/整数列表），因此不引入 JSON 库，直接用正则提取。
 *
 * <p>目录基线：游戏 V1.0.7（build 10011）的 pck。每次游戏大版本更新后若出现「未知道具 #ID」，
 * 用 {@code .workbuddy/re/build_catalog_106.py} 重新提取并合并即可（合并而非覆盖，
 * 这样已被官方下架的旧道具名仍然保留，不会变成未知）。
 */
public final class ItemCatalog {

    /** 从游戏资源提取的名称表。 */
    private static final String NAMES_RESOURCE = "/catalog/item_names.json";

    /** 分区表。 */
    private static final String SECTIONS_RESOURCE = "/catalog/item_sections.json";

    /** 当前版本可获得的装备 ID 表（每行一个整数）。 */
    private static final String EQUIPMENT_RESOURCE = "/catalog/equipment_ids.json";

    /** 装备元数据表：id → [部位, 质量]，决定这件装备能不能强化。 */
    private static final String META_RESOURCE = "/catalog/equipment_meta.json";

    /**
     * 装备部位类型（与游戏 {@code 装备图标数据.部位类型} 一致）：
     * 头=1 项链=2 护手=3 武器=4 戒指=5 衣服=6 腰带=7 护腿=8 鞋子=9 玉佩=10。
     */
    public static final int PART_NECKLACE = 2;
    public static final int PART_RING = 5;
    public static final int PART_JADE = 10;

    /** 质量类型橙色（与游戏 {@code 装备图标数据.质量类型.橙色} 一致，橙色禁止强化）。 */
    public static final int QUALITY_ORANGE = 5;

    /** 质量名称（下标 = 质量值，0 未使用；与游戏 质量类型 枚举一致）。 */
    private static final String[] QUALITY_NAMES = {
            null, "白色", "绿色", "蓝色", "紫色", "橙色", "红色", "金色"};

    /** 强化等级绝对上限（紫色品质封顶）。 */
    public static final int MAX_STRENGTHEN = 12;

    /** 背包分区：道具。 */
    public static final String SECTION_ITEMS = "items";

    /** 背包分区：材料。 */
    public static final String SECTION_MATERIALS = "materials";

    /** 背包分区：装备（可添加：按游戏同款 {uid, id} 条目追加进背包）。 */
    public static final String SECTION_EQUIPMENT = "equipment";

    private static final Pattern PAIR = Pattern.compile("\"(\\d+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern INT = Pattern.compile("\\d+");

    /** 目录条目。 */
    public record Entry(int id, String name, String section) {
    }

    private final Map<Integer, String> names;
    private final Map<Integer, String> sections;
    private final Set<Integer> equipmentIds;

    /** 装备元数据：id → [部位, 质量]（int[2]）。 */
    private final Map<Integer, int[]> equipmentMeta;

    private ItemCatalog(Map<Integer, String> names, Map<Integer, String> sections,
                        Set<Integer> equipmentIds, Map<Integer, int[]> equipmentMeta) {
        this.names = names;
        this.sections = sections;
        this.equipmentIds = equipmentIds;
        this.equipmentMeta = equipmentMeta;
    }

    /**
     * 从类路径加载内置目录。
     *
     * @return 目录实例
     * @throws IllegalStateException 资源缺失（打包不完整）
     */
    public static ItemCatalog loadDefault() {
        Map<Integer, String> names = parse(read(NAMES_RESOURCE));
        Map<Integer, String> sections = parse(read(SECTIONS_RESOURCE));
        Set<Integer> equipmentIds = parseInts(read(EQUIPMENT_RESOURCE));
        Map<Integer, int[]> meta = parseMeta(read(META_RESOURCE));
        return new ItemCatalog(names, sections, equipmentIds, meta);
    }

    /**
     * 判断一件装备能否强化。
     *
     * <p>与游戏规则一致（装备强化计算器只给武器与防具写了公式，
     * {@code _本地强化装备} 又明令「橙色装备不能强化」）：
     * 首饰（项链/戒指/玉佩）与橙色装备一律不可强化。
     *
     * @param id 装备 ID
     * @return 可强化返回 true；元数据里查不到的老装备按可强化处理（不做过度拦截）
     */
    public boolean canStrengthen(int id) {
        return strengthenCap(id) > 0 && !isJewelry(id);
    }

    /**
     * 取一件装备的强化上限。
     *
     * <p>与游戏 {@code 获取品质最高强化等级} 一致：绿 4、蓝 8、紫 12；
     * 白色与橙色上限为 0（不能强化）。元数据里查不到的老装备按 12 处理。
     *
     * @param id 装备 ID
     * @return 强化上限（0 表示不可强化）
     */
    public int strengthenCap(int id) {
        int[] meta = equipmentMeta.get(id);
        if (meta == null || meta.length < 2) {
            return MAX_STRENGTHEN;
        }
        return switch (meta[1]) {
            case 2 -> 4;   // 绿色
            case 3 -> 8;   // 蓝色
            case 4 -> 12;  // 紫色
            default -> 0;  // 白色/橙色/其他：不能强化
        };
    }

    /**
     * 判断是否首饰（项链/戒指/玉佩，游戏不给它们写强化公式）。
     *
     * @param id 装备 ID
     * @return 是首饰返回 true；元数据查不到按不是处理
     */
    public boolean isJewelry(int id) {
        int[] meta = equipmentMeta.get(id);
        if (meta == null || meta.length < 1) {
            return false;
        }
        int part = meta[0];
        return part == PART_NECKLACE || part == PART_RING || part == PART_JADE;
    }

    /**
     * 取物品名称。
     *
     * @param id 物品 ID
     * @return 名称；未知时返回「未知道具 #ID」
     */
    public String name(int id) {
        String name = names.get(id);
        return name == null || name.isBlank() ? "未知道具 #" + id : name;
    }

    /**
     * 取展示名称：装备在名字后追加品质（如「木棍（绿色）」）。
     *
     * <p>不少装备重名但品质不同（绿/蓝/紫属性天差地别），不加后缀没法区分。
     * 非装备与元数据缺失的老装备返回原名。
     *
     * @param id 物品 ID
     * @return 带品质后缀的名称
     */
    public String displayName(int id) {
        int[] meta = equipmentMeta.get(id);
        if (meta == null || meta.length < 2) {
            return name(id);
        }
        int quality = meta[1];
        if (quality < 1 || quality >= QUALITY_NAMES.length) {
            return name(id);
        }
        return name(id) + "（" + QUALITY_NAMES[quality] + "）";
    }

    /**
     * 判断目录里是否有这个 ID。
     *
     * @param id 物品 ID
     * @return 有名称返回 true
     */
    public boolean known(int id) {
        return names.containsKey(id);
    }

    /**
     * 取物品所属分区。
     *
     * @param id 物品 ID
     * @return {@code items} / {@code materials} / {@code equipment}，未知按道具处理
     */
    public String section(int id) {
        if (equipmentIds.contains(id)) {
            return SECTION_EQUIPMENT;
        }
        String section = sections.get(id);
        if (section == null) {
            return SECTION_ITEMS;
        }
        return switch (section) {
            case SECTION_MATERIALS -> section;
            default -> SECTION_ITEMS;
        };
    }

    /**
     * 单格堆叠上限：游戏在入境体检时把超过 9999 的堆叠直接截到 9999。
     *
     * <p>这是 1.1.1 实测出来的硬规则，见 {@code anti_ce.entry_audit.stack}：
     * 起死回生草 70961 → 9999、灵兽经验包 80001 → 9999、清水 91339 → 9999。
     */
    public static final int DEFAULT_STACK_LIMIT = 9999;

    /**
     * 单格上限低于默认值的特例。
     *
     * <p>实测来源：1.1.1 入境体检把双倍经验卡 197 截到 99
     * （{@code anti_ce.entry_audit.over}），说明这类卡片走的是 99 上限而非 9999。
     */
    private static final Map<Integer, Integer> SPECIAL_STACK_LIMITS = Map.of(
            1703002, 99      // 双倍经验卡
    );

    /**
     * 该物品的单格堆叠上限。装备不参与堆叠，调用方应先排除装备分区。
     *
     * @param id 物品 ID
     * @return 上限数量（道具/材料默认 9999，特例见 {@link #SPECIAL_STACK_LIMITS}）
     */
    public int stackLimit(int id) {
        return SPECIAL_STACK_LIMITS.getOrDefault(id, DEFAULT_STACK_LIMIT);
    }

    /**
     * 列出可以「添加」的候选物品：道具、材料与当前版本可获得的装备。
     *
     * <p>排序规则与界面一致：先按分区（道具 → 材料 → 装备），同分区内按物品 ID 升序。
     * 装备允许与已拥有的重复（同一件装备可以有多件），所以 {@code excludeIds}
     * 只对道具与材料生效。
     *
     * @param excludeIds 需要排除的 ID（通常传当前角色已拥有的道具/材料）
     * @return 排好序的候选列表
     */
    public List<Entry> candidates(Collection<Integer> excludeIds) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : sections.entrySet()) {
            String section = e.getValue();
            if (SECTION_EQUIPMENT.equals(section)
                    || (excludeIds != null && excludeIds.contains(e.getKey()))) {
                continue;
            }
            out.add(new Entry(e.getKey(), name(e.getKey()), section));
        }
        // 装备单独补：sections 表里没有装备条目，从装备 ID 表生成（名字带品质后缀）
        for (Integer id : equipmentIds) {
            out.add(new Entry(id, displayName(id), SECTION_EQUIPMENT));
        }
        out.sort(Comparator.comparingInt(ItemCatalog::sectionRank)
                .thenComparingInt(Entry::id));
        return out;
    }

    /**
     * 分区的展示顺序权重（道具 0、材料 1、装备 2，其余排最后）。
     *
     * @param entry 条目
     * @return 权重
     */
    private static int sectionRank(Entry entry) {
        return switch (entry.section()) {
            case SECTION_ITEMS -> 0;
            case SECTION_MATERIALS -> 1;
            case SECTION_EQUIPMENT -> 2;
            default -> 3;
        };
    }

    /**
     * 目录条目总数（用于日志）。
     *
     * @return 名称条目数
     */
    public int size() {
        return names.size();
    }

    /**
     * 解析装备元数据 JSON（{@code {"1100001":[5,3],...}}，值为 [部位, 质量]）。
     *
     * @param text 文件内容
     * @return id → [部位, 质量]
     */
    private static Map<Integer, int[]> parseMeta(String text) {
        Map<Integer, int[]> out = new LinkedHashMap<>();
        // "id":[a,b] —— 值为两元素整数数组
        Matcher pair = Pattern.compile("\"(\\d+)\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]").matcher(text);
        while (pair.find()) {
            out.put(Integer.parseInt(pair.group(1)),
                    new int[]{Integer.parseInt(pair.group(2)), Integer.parseInt(pair.group(3))});
        }
        return out;
    }

    private static Map<Integer, String> parse(String text) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Matcher matcher = PAIR.matcher(text);
        while (matcher.find()) {
            try {
                out.put(Integer.parseInt(matcher.group(1)), unescape(matcher.group(2)));
            } catch (NumberFormatException ignored) {
                // ID 不是数字的条目直接跳过，不影响其余数据
            }
        }
        return out;
    }

    /**
     * 从整数数组 JSON（{@code [1,2,3]}）里提取全部整数。
     *
     * @param text 文件内容
     * @return 有序 ID 集合
     */
    private static Set<Integer> parseInts(String text) {
        Set<Integer> out = new TreeSet<>();
        Matcher matcher = INT.matcher(text);
        while (matcher.find()) {
            out.add(Integer.parseInt(matcher.group()));
        }
        return out;
    }

    /**
     * 还原 JSON 字符串里最常见的几种转义（这批数据里基本只有反斜杠与引号）。
     */
    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'u' -> {
                    if (i + 4 < raw.length()) {
                        try {
                            out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append("\\u");
                        }
                    } else {
                        out.append("\\u");
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static String read(String resource) {
        try (InputStream in = ItemCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少内置资源：" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取内置资源失败：" + resource, e);
        }
    }
}
