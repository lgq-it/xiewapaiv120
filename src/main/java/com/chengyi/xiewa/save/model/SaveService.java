package com.chengyi.xiewa.save.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chengyi.xiewa.save.core.SaveFormatException;
import com.chengyi.xiewa.save.format.VariantNavigator;
import com.chengyi.xiewa.save.format.VariantSpan;
import com.chengyi.xiewa.save.format.VariantTypes;
import com.chengyi.xiewa.save.keys.SaveKeys;

/**
 * 存档业务层：把字节级操作包装成「改功夫豆」「加物品」这类语义操作。
 *
 * <p>所有写操作都遵循同一条铁律：<b>只改目标数值的字节，其余一个字节不动</b>，
 * 然后代数 +1、重算 HMAC、备份原档、落盘、回读自检。这样游戏端看到的是一份
 * 完全合法的存档，而不是"被重新序列化过"的可疑文件。
 */
public final class SaveService {

    /** 背包三个分区的名字，顺序即界面展示顺序。 */
    public static final List<String> SECTIONS = List.of(
            ItemCatalog.SECTION_ITEMS, ItemCatalog.SECTION_MATERIALS, ItemCatalog.SECTION_EQUIPMENT);

    /** 一次添加物品的结果。 */
    public record AddResult(int itemId, String name, String section, long count,
                            int sectionSize, boolean merged) {
    }

    private final Path path;
    private final byte[] key;
    private final SaveDocument document;
    private final ItemCatalog catalog;

    private SaveService(Path path, byte[] key, SaveDocument document, ItemCatalog catalog) {
        this.path = path;
        this.key = key;
        this.document = document;
        this.catalog = catalog;
    }

    /**
     * 打开一份存档（自行解析密钥：本机 DPAPI 优先，keys.json 兜底）。
     *
     * <p>这里会<b>按候选顺序逐个试解</b>，第一个解得开的就用，并记住它。
     * 这样做是为了兼容"存档从别的电脑拷过来、目录名已经不是原账号 ID"的场景：
     * 只按目录名精确取密钥会直接报错，而挨个试通常能正常打开。
     *
     * @param path    存档路径
     * @param catalog 物品目录
     * @return 业务对象
     * @throws IOException         读盘失败
     * @throws SaveFormatException 所有候选密钥都解不开，或存档结构损坏
     */
    public static SaveService open(Path path, ItemCatalog catalog) throws IOException, SaveFormatException {
        List<SaveKeys.Resolution> candidates = SaveKeys.candidates(path);
        if (candidates.isEmpty()) {
            // 没有任何候选时让 resolve 抛出，它的错误提示最详细
            candidates = List.of(SaveKeys.resolve(path));
        }

        SaveFormatException last = null;
        String lastDetail = "";
        for (SaveKeys.Resolution candidate : candidates) {
            try {
                SaveDocument document = SaveDocument.load(path, candidate.key());
                SaveKeys.remember(path, candidate);
                return new SaveService(path, candidate.key(), document, catalog);
            } catch (SaveFormatException e) {
                // 密钥不对时表现为"MD5 校验失败"，换下一个候选继续试
                last = e;
                lastDetail = candidate.detail();
            }
        }
        throw new SaveFormatException(
                "试了 %d 个候选密钥都解不开这份存档（最后一个来自 %s）。\n"
                        .formatted(candidates.size(), lastDetail)
                        + "若存档来自另一台电脑，请在那台电脑上生成 keys.json 后放到修改器目录。\n"
                        + "最后一个错误：" + (last == null ? "未知" : last.getMessage()),
                last);
    }

    /**
     * 用指定密钥打开存档。
     *
     * @param path    存档路径
     * @param key     32 字节密钥
     * @param catalog 物品目录
     * @return 业务对象
     * @throws IOException         读盘失败
     * @throws SaveFormatException 解密或格式异常
     */
    public static SaveService open(Path path, byte[] key, ItemCatalog catalog)
            throws IOException, SaveFormatException {
        return new SaveService(path, key, SaveDocument.load(path, key), catalog);
    }

    /**
     * 存档路径。
     *
     * @return 路径
     */
    public Path path() {
        return path;
    }

    /**
     * 账号 ID（存档目录名）。
     *
     * @return 账号 ID
     */
    public String profileId() {
        Path parent = path.toAbsolutePath().getParent();
        return parent == null ? "" : parent.getFileName().toString();
    }

    /**
     * 物品目录。
     *
     * @return 目录
     */
    public ItemCatalog catalog() {
        return catalog;
    }

    /**
     * 当前代数。
     *
     * @return 代数
     * @throws SaveFormatException 字段异常
     */
    public long generation() throws SaveFormatException {
        return document.generation();
    }

    // ---------------------------------------------------------------- 读取

    /**
     * 账户级数值快照。
     *
     * @return 快照
     */
    @SuppressWarnings("unchecked")
    public Currencies currencies() {
        Map<Object, Object> payload = document.payload();
        Map<Object, Object> currencies = asMap(payload.get("account_currencies"));
        Map<Object, Object> ledger = asMap(currencies.get("tongbao_ledger"));
        Map<Object, Object> signin = asMap(payload.get("daily_signin"));

        long buySum = 0;
        for (Object raw : asList(ledger.get("buys"))) {
            buySum += asLong(asMap(raw).get("p"));
        }
        // 1.0.7 新增：drops / pend 都是「日期 → 金额」的表，求和分别得到掉落明细与待入账合计
        long dropSum = 0;
        for (Object raw : asMap(ledger.get("drops")).values()) {
            dropSum += asLong(raw);
        }
        long pend = 0;
        for (Object raw : asMap(ledger.get("pend")).values()) {
            pend += asLong(raw);
        }
        return new Currencies(
                asLong(currencies.get("tongbao")),
                asLong(currencies.get("demon_points")),
                asLong(ledger.get("spent")),
                asLong(ledger.get("admin")),
                asLong(ledger.get("legacy")),
                buySum,
                (int) asLong(signin.get("total")),
                (int) asLong(signin.get("cycle_day")),
                asString(signin.get("last_date")),
                asLong(ledger.get("drop")),
                asLong(ledger.get("god")),
                pend,
                dropSum);
    }

    /**
     * 存档的结构版本。
     *
     * <p>游戏 1.0.7 起在存档里写 {@code schema_version} 和 {@code save_version}，
     * 用于判断「这份存档的结构还是不是我认识的那一套」。
     *
     * @return 版本快照
     */
    public SaveMeta meta() {
        Map<Object, Object> payload = document.payload();
        Map<Object, Object> version = asMap(payload.get("save_version"));
        return new SaveMeta(
                asLong(payload.get("schema_version")),
                asString(version.get("last_client_version")),
                asLong(version.get("last_client_build")),
                asLong(version.get("minimum_client_build")));
    }

    /**
     * 存档里记录的反作弊状态。
     *
     * <p>{@code anti_ce} 是游戏内建反作弊模块的状态位：它统计「检测到作弊工具的
     * 次数」（{@code tool_hits}）以及是否已被标记（{@code sealed}）。该模块盯的是
     * 游戏运行中的内存操作，不检查磁盘文件，所以离线改档不会让它增加。
     *
     * @return 反作弊状态
     */
    public AntiCheatState antiCheat() {
        Map<Object, Object> anti = asMap(document.payload().get("anti_ce"));
        return new AntiCheatState(
                asLong(anti.get("tool_hits")),
                asLong(anti.get("closes")),
                Boolean.TRUE.equals(anti.get("sealed")),
                Boolean.TRUE.equals(anti.get("repair_pending")),
                asLong(anti.get("client_mod_warned")),
                asLong(anti.get("tamper_pending")),
                asString(anti.get("nonce")));
    }

    /**
     * 游戏 1.0.8 起对少数「稀有材料」做的材料审计结果。
     *
     * <p>1.0.8 登录时按账号累计游戏时长算出一个「合理持有量」{@code cap}，
     * 持有的超出部分直接回收（玩家看到的提示是「稀有材料超出异常，已回收部分道具」）。
     * 审计结果就写在存档 {@code anti_ce.audit_r2.items} 里，是账号级的。
     *
     * @param itemId 材料 ID
     * @param held   当次审计时的账号持有量（历史快照，只作参考）
     * @param cap    游戏认定的账号级上限
     * @param taken  当次审计回收掉的数量
     */
    public record MaterialCap(int itemId, long held, long cap, long taken) {

        /** @return 游戏是否给出了有效上限（0 表示不审计这件材料） */
        public boolean limited() {
            return cap > 0;
        }
    }

    /**
     * 读出材料审计给出的上限表。
     *
     * <p>来源按版本递进，只为读、不写：
     * <ul>
     *   <li><b>1.1.1 起</b>：{@code anti_ce.entry_audit.rare}，入境体检每次登录重算，
     *       {@code cap} 会随账号时长增长，是最准的一份；</li>
     *   <li><b>1.0.6~1.1.0</b>：{@code anti_ce.audit_r2.items}，一轮性审计的历史记录，
     *       1.1.1 会把它的 {@code taken} 复原成老值，已不代表当前判定，仅作兜底。</li>
     * </ul>
     *
     * @return 材料 ID → 审计上限
     */
    public Map<Integer, MaterialCap> materialCaps() {
        Map<Object, Object> anti = asMap(document.payload().get("anti_ce"));
        // 1.1.1：入境体检结果优先
        Map<Object, Object> items = asMap(asMap(anti.get("entry_audit")).get("rare"));
        if (items.isEmpty()) {
            // 老版本兜底：只有一轮性审计
            items = asMap(asMap(anti.get("audit_r2")).get("items"));
        }
        Map<Integer, MaterialCap> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : items.entrySet()) {
            // 字典键是自描述字符串（"1500579"），不是数值
            int itemId;
            try {
                itemId = Integer.parseInt(String.valueOf(entry.getKey()).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (itemId <= 0) {
                continue;
            }
            Map<Object, Object> one = asMap(entry.getValue());
            out.put(itemId, new MaterialCap(itemId, asLong(one.get("held")),
                    asLong(one.get("cap")), asLong(one.get("taken"))));
        }
        return out;
    }

    /**
     * 该材料在账号内所有角色身上的实时合计。
     *
     * <p>审计是账号级的：两个角色各拿一半，合起来超了照样被削。
     * <b>账号仓库也要算</b>——1.1.1 的体检把仓库和背包一起统计，
     * 只算背包会让「背包没超、仓库早已爆仓」的情况被放过。
     *
     * @param itemId 材料 ID
     * @return 合计数量
     */
    public long materialTotal(int itemId) {
        long total = 0;
        for (CharacterView character : characters()) {
            for (String section : List.of(ItemCatalog.SECTION_ITEMS, ItemCatalog.SECTION_MATERIALS)) {
                for (ItemView item : items(character.slot(), section)) {
                    if (item.id() == itemId) {
                        total += item.count();
                    }
                }
            }
        }
        // 账号仓库（account_storehouse.entries）：与背包同属账号级持有
        for (Object raw : asList(asMap(document.payload().get("account_storehouse")).get("entries"))) {
            Map<Object, Object> entry = asMap(raw);
            if (asLong(entry.get("id")) == itemId) {
                total += asLong(entry.get("count"));
            }
        }
        return total;
    }

    /**
     * 某条目在当前账号持有量下还能设成多少。
     *
     * @param slot    卡槽
     * @param section 分区
     * @param index   数组下标
     * @return 允许的最大数量；不受审计限制时返回 -1
     */
    public long materialLimit(int slot, String section, int index) {
        if (ItemCatalog.SECTION_EQUIPMENT.equals(section)) {
            return -1;
        }
        int itemId = 0;
        long current = 0;
        for (ItemView item : items(slot, section)) {
            if (item.index() == index) {
                itemId = item.id();
                current = item.count();
                break;
            }
        }
        if (itemId <= 0) {
            return -1;
        }
        MaterialCap cap = materialCaps().get(itemId);
        if (cap == null || !cap.limited()) {
            return -1;
        }
        long others = materialTotal(itemId) - current;
        return Math.max(0, cap.cap() - others);
    }

    /**
     * 材料审计上限校验：超了直接拦下来，附上游戏算出来的上限值。
     *
     * @param slot    卡槽
     * @param section 分区
     * @param index   数组下标
     * @param count   期望数量
     * @throws SaveFormatException 超出审计上限
     */
    private void checkMaterialLimit(int slot, String section, int index, long count)
            throws SaveFormatException {
        long max = materialLimit(slot, section, index);
        if (max >= 0 && count > max) {
            throw new SaveFormatException(
                    "超出游戏认定的材料上限：这件材料账号内最多还能放 %d 个。".formatted(max)
                            + "\n上限由游戏登录体检按账号时长算出（背包 + 仓库一起统计），"
                            + "超出的部分下次登录会被直接回收。");
        }
    }

    /**
     * 全部非空角色卡槽。
     *
     * @return 角色快照列表
     */
    public List<CharacterView> characters() {
        Map<Object, Object> payload = document.payload();
        List<Object> slots = asList(payload.get("characters"));
        List<CharacterView> out = new ArrayList<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            Map<Object, Object> character = asMap(slots.get(slot));
            if (character.isEmpty()) {
                continue;
            }
            out.add(toView(slot, character));
        }
        return out;
    }

    /**
     * 指定卡槽的角色快照。
     *
     * @param slot 卡槽
     * @return 快照；空槽返回 {@code null}
     */
    public CharacterView character(int slot) {
        List<Object> slots = asList(document.payload().get("characters"));
        if (slot < 0 || slot >= slots.size()) {
            return null;
        }
        Map<Object, Object> character = asMap(slots.get(slot));
        return character.isEmpty() ? null : toView(slot, character);
    }

    /**
     * 读取某个背包分区的物品列表。
     *
     * @param slot    卡槽
     * @param section 分区
     * @return 物品列表（顺序与存档内一致）
     */
    public List<ItemView> items(int slot, String section) {
        List<Object> slots = asList(document.payload().get("characters"));
        if (slot < 0 || slot >= slots.size()) {
            return List.of();
        }
        Map<Object, Object> inventory = asMap(asMap(slots.get(slot)).get("inventory"));
        List<Object> raw = asList(inventory.get(section));
        List<ItemView> out = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Map<Object, Object> item = asMap(raw.get(index));
            int id = (int) asLong(item.get("id"));
            // 装备一条就是一件，存档里没有 count 字段；显示层面按 1 件算
            long count = ItemCatalog.SECTION_EQUIPMENT.equals(section)
                    ? 1 : asLong(item.get("count"));
            out.add(new ItemView(index, id, catalog.displayName(id), count, section,
                    asLong(item.get("strengthen_level")), null));
        }
        return out;
    }

    /**
     * 某角色已拥有的全部物品 ID（用于添加物品时排除）。
     *
     * @param slot 卡槽
     * @return ID 集合
     */
    public Set<Integer> ownedIds(int slot) {
        Set<Integer> out = new HashSet<>();
        for (ItemView item : items(slot, ItemCatalog.SECTION_ITEMS)) {
            out.add(item.id());
        }
        for (ItemView item : items(slot, ItemCatalog.SECTION_MATERIALS)) {
            out.add(item.id());
        }
        return out;
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 改某个角色的功夫豆。
     *
     * @param slot  卡槽
     * @param value 新值
     * @throws SaveFormatException 路径异常
     */
    public void setBean(int slot, long value) throws SaveFormatException {
        document.patchInt(List.of("characters", slot, "progression", "kungfu_bean"), value);
    }

    /**
     * 改魔点。
     *
     * @param value 新值
     * @throws SaveFormatException 路径异常
     */
    public void setDemonPoints(long value) throws SaveFormatException {
        document.patchInt(List.of("account_currencies", "demon_points"), value);
    }

    /** 1.2.0 魔渊每周渊晶上限（默认值，来自魔渊里程碑表）。 */
    private static final long ABYSS_WEEKLY_CAP = 8000;

    /** 魔渊渊晶的账号级周进度快照。 */
    public record AbyssWeekly(long crystal, long weekCrystal, long cap, String week) {
    }

    /**
     * 读取渊晶余额与本周年进度。
     *
     * @return 快照；存档还没有魔渊字段时返回空进度
     */
    public AbyssWeekly abyssWeekly() {
        Map<Object, Object> payload = document.payload();
        Map<Object, Object> currencies = asMap(payload.get("account_currencies"));
        Map<Object, Object> acct = asMap(payload.get("abyss_acct"));
        String week = isoWeekKey(LocalDate.now());
        String stored = asString(acct.get("week"));
        long weekCrystal = week.equals(stored) ? asLong(acct.get("week_crystal")) : 0;
        return new AbyssWeekly(
                asLong(currencies.get("abyss_crystal")),
                weekCrystal,
                ABYSS_WEEKLY_CAP,
                week);
    }

    /**
     * 把本周渊晶进度补满到每周上限，余额同额增加。
     *
     * <p>与通宝一样，这里不做凭空充值，只补“本周还没拿到的额度”。渊晶没有独立账本，
     * 但必须连同 {@code abyss_acct.week_crystal / week} 一起原地改写，否则游戏 UI
     * 里本周进度和余额对不上。
     *
     * @return 本次新增的渊晶数量
     * @throws SaveFormatException 存档还没有魔渊字段
     */
    public long fillAbyssWeekly() throws SaveFormatException {
        Map<Object, Object> payload = document.payload();
        Map<Object, Object> currencies = asMap(payload.get("account_currencies"));
        Map<Object, Object> acct = asMap(payload.get("abyss_acct"));
        if (!payload.containsKey("abyss_acct") || !currencies.containsKey("abyss_crystal")) {
            throw new SaveFormatException(
                    "存档里还没有魔渊渊晶字段。\n请先进游戏打开一次“虚空魔渊”（或打一局），让游戏生成渊晶数据后再回来拉满。");
        }
        String week = isoWeekKey(LocalDate.now());
        String stored = asString(acct.get("week"));
        long weekCrystal = week.equals(stored) ? asLong(acct.get("week_crystal")) : 0;
        long add = Math.max(0, ABYSS_WEEKLY_CAP - weekCrystal);
        if (add == 0) {
            return 0;
        }
        document.patchInt(List.of("account_currencies", "abyss_crystal"),
                asLong(currencies.get("abyss_crystal")) + add);
        document.patchInt(List.of("abyss_acct", "week_crystal"), ABYSS_WEEKLY_CAP);
        document.patchString(List.of("abyss_acct", "week"), week);
        return add;
    }

    /** 计算 ISO 周一的日期键，和游戏里的 week_key 一致（如 W2026-11-16）。 */
    private static String isoWeekKey(LocalDate today) {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        return "W" + monday;
    }

    /**
     * 改某件物品的数量。
     *
     * @param slot    卡槽
     * @param section 分区
     * @param index   数组下标
     * @param count   新数量
     * @throws SaveFormatException 路径异常
     */
    public void setItemCount(int slot, String section, int index, long count) throws SaveFormatException {
        if (ItemCatalog.SECTION_EQUIPMENT.equals(section)) {
            throw new SaveFormatException("装备一条就是一件，没有数量字段，不支持改数量");
        }
        if (count < 0) {
            throw new SaveFormatException("数量不能为负数：" + count);
        }
        checkStackLimit(itemIdAt(slot, section, index), count);
        checkMaterialLimit(slot, section, index, count);
        document.patchInt(List.of("characters", slot, "inventory", section, index, "count"), count);
    }

    /**
     * 单格堆叠上限校验：超过游戏上限的值登录时会被体检直接截断。
     *
     * <p>1.1.1 实测：超过 9999 的堆叠一律截到 9999，双倍经验卡这类截到 99。
     * 与其写进去被削（还会弹「数据调整提示」留痕），不如在修改器这一层就拦住。
     *
     * @param itemId 物品 ID
     * @param count  期望数量
     * @throws SaveFormatException 超出堆叠上限
     */
    private void checkStackLimit(int itemId, long count) throws SaveFormatException {
        if (itemId <= 0) {
            return;
        }
        int limit = catalog.stackLimit(itemId);
        if (count > limit) {
            throw new SaveFormatException(
                    "超出单格堆叠上限：%s 最多只能放 %d 个（你填了 %d）。"
                            .formatted(catalog.displayName(itemId), limit, count)
                            + "\n游戏在下次登录体检时会把超出部分直接截到 "
                            + limit + "，所以修改器不写入。"
                            + "\n要更多就分到不同角色的背包里，或分多次获取。");
        }
    }

    /**
     * 取指定位置上的物品 ID（用于堆叠校验）。
     *
     * @param slot    卡槽
     * @param section 分区
     * @param index   数组下标
     * @return 物品 ID；找不到返回 0
     */
    private int itemIdAt(int slot, String section, int index) {
        try {
            for (ItemView item : items(slot, section)) {
                if (item.index() == index) {
                    return item.id();
                }
            }
        } catch (RuntimeException ignored) {
            // 结构异常时交给后续 patchInt 报错，这里不抢报
        }
        return 0;
    }

    /** 游戏的强化等级上限：读取侧按 {@code clampi(0, 12)} 收敛。 */
    public static final int MAX_STRENGTHEN = 12;

    /**
     * 设置一件装备的强化等级（背包件与身上穿的都支持）。
     *
     * <p>游戏读档时对缺失的 {@code strengthen_level} 按 0 处理、对超界值按
     * {@code clampi(0, 12)} 收敛，属性加成在运行时按等级重算，核账不检查装备数值，
     * 所以直接原地改写/补键都是安全的。条目原本没有该字段时，往条目字典里
     * 追加一个键（字典元素均为自描述编码，追加键值对 + 计数 +1 即可）。
     *
     * @param slot  卡槽
     * @param item  目标装备（背包件用 {@code index}，穿戴件用 {@code part} 定位）
     * @param level 期望强化等级（超出 0~12 按 12 收敛）
     * @return 实际写入的等级
     * @throws SaveFormatException 定位失败或结构异常
     */
    public int setStrengthenLevel(int slot, ItemView item, int level) throws SaveFormatException {
        int lv = Math.max(0, Math.min(MAX_STRENGTHEN, level));
        List<Object> base = item.worn()
                ? List.of("characters", slot, "equipment", item.part())
                : List.of("characters", slot, "inventory", "equipment", item.index());
        VariantNavigator navigator = document.navigator();
        int dictOffset = navigator.locate(document.payloadOffset(), base);
        boolean exists = navigator.dictEntries(dictOffset).stream()
                .anyMatch(e -> "strengthen_level".equals(String.valueOf(e.key())));
        if (exists) {
            List<Object> path = new ArrayList<>(base);
            path.add("strengthen_level");
            document.patchInt(path, lv);
        } else {
            appendDictInt(navigator, dictOffset, "strengthen_level", lv);
        }
        return lv;
    }

    /**
     * 向一个「元素自描述」的字典末尾追加 {@code 字符串键: 整数值}。
     *
     * <p>与数组追加同一套路：条目字节接到字典尾部、元素计数 +1、重建信封。
     * 装备条目的键值类型标志都是 {@code CONTAINER_TYPE_NONE}，追加自描述
     * 键值对即可被游戏正常解析。
     */
    private void appendDictInt(VariantNavigator navigator, int dictOffset, String key, long value)
            throws SaveFormatException {
        int countOffset = dictCountOffset(navigator, dictOffset);
        int rawCount = navigator.readU32(countOffset);
        byte[] keyBytes = ItemTemplate.encodeString(key);
        byte[] valueBytes = ItemTemplate.encodeInt(value);
        byte[] pair = new byte[keyBytes.length + valueBytes.length];
        System.arraycopy(keyBytes, 0, pair, 0, keyBytes.length);
        System.arraycopy(valueBytes, 0, pair, keyBytes.length, valueBytes.length);

        document.splice(navigator.walkToEnd(dictOffset), pair);
        document.refreshPlainLength();
        document.navigator().writeU32(countOffset,
                (rawCount & 0x80000000) | ((rawCount & 0x7FFFFFFF) + 1));
        document.rebuildEnvelope();
    }

    /** 字典元素计数字段的偏移（键/值共享类型标志逐个跳过后的第一个 u32）。 */
    private int dictCountOffset(VariantNavigator navigator, int dictOffset) throws SaveFormatException {
        int header = navigator.readU32(dictOffset);
        if ((header & 0xFF) != VariantTypes.DICTIONARY) {
            throw new SaveFormatException(
                    "目标不是字典，而是 %s".formatted(VariantTypes.name(header & 0xFF)));
        }
        int keyKind = (header >>> 16) & 3;
        int valueKind = (header >>> 18) & 3;
        int cursor = navigator.skipContainerType(keyKind, dictOffset + VariantSpan.HEADER_SIZE);
        return navigator.skipContainerType(valueKind, cursor);
    }

    /**
     * 把所有角色的所有道具/材料数量统一改成同一个值（命令行批量场景用）。
     *
     * <p>装备区不动：装备的可用性受游戏内解锁逻辑控制，改数量容易触发异常校验。
     *
     * @param count 新数量
     * @return 实际被改动的条目数
     * @throws SaveFormatException 路径异常
     */
    public int setAllItemCounts(long count) throws SaveFormatException {
        if (count < 0) {
            throw new SaveFormatException("数量不能为负数：" + count);
        }
        int changed = 0;
        for (CharacterView character : characters()) {
            for (String section : List.of(ItemCatalog.SECTION_ITEMS, ItemCatalog.SECTION_MATERIALS)) {
                for (ItemView item : items(character.slot(), section)) {
                    // 批量改按各自上限收敛：上限 99 的卡片不会因为这里填 9999 而整体失败
                    long target = Math.min(count, catalog.stackLimit(item.id()));
                    setItemCount(character.slot(), section, item.index(), target);
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * 按物品 ID 改数量，跨所有角色生效（同一 ID 出现在多个分区也一起改）。
     *
     * @param itemId 物品 ID
     * @param count  新数量
     * @return 实际被改动的条目数
     * @throws SaveFormatException 路径异常
     */
    public int setItemCountById(int itemId, long count) throws SaveFormatException {
        if (count < 0) {
            throw new SaveFormatException("数量不能为负数：" + count);
        }
        int changed = 0;
        for (CharacterView character : characters()) {
            for (String section : List.of(ItemCatalog.SECTION_ITEMS, ItemCatalog.SECTION_MATERIALS)) {
                for (ItemView item : items(character.slot(), section)) {
                    if (item.id() == itemId) {
                        setItemCount(character.slot(), section, item.index(), count);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /**
     * 今天这一天的副本掉落情况（界面用它显示「今日额度」并决定按钮状态）。
     *
     * @param day      今天（yyyy-MM-dd）
     * @param used     今天已占用（掉落明细 + 待入账凭证）
     * @param free     今天还能补多少（已按 5 取整）
     * @param recorded 存档里是否已经有今天的掉落记录
     */
    public record TodayDrop(String day, long used, long free, boolean recorded) {
    }

    /**
     * 读取「今天」的副本掉落额度。
     *
     * @return 今日额度快照
     */
    public TodayDrop todayDrop() {
        LocalDate today = LocalDate.now();
        String day = today.toString();
        Map<Object, Object> ledger = ledgerMap();
        Map<Object, Object> drops = asMap(ledger.get("drops"));
        long used = HellTongbao.usage(drops, asMap(ledger.get("pend")))
                .getOrDefault(day, 0L);
        return new TodayDrop(day, used, HellTongbao.freeOn(used), drops.containsKey(day));
    }

    /**
     * 补满今天的副本额度：把「每日上限 − 今天已掉落」这一笔记进今天的掉落明细。
     *
     * <p>这是通宝<b>唯一</b>的修改动作，刻意只走游戏自己的记账渠道，不做凭空充值：
     * <ul>
     *   <li>只写今天，不往前补、不往后摊；</li>
     *   <li>金额由上限反推，因此天然 ≤ 200、且是 5 的倍数（游戏「数量表」的粒度）；</li>
     *   <li>余额、{@code drop} 总额、{@code drops} 当天额三处同时加同一个数，账本等式不变。</li>
     * </ul>
     *
     * <p>存档补丁是原地改写，写不进不存在的日期键，所以今天必须已经在副本里掉过通宝
     * （存档里已有今天的记录），否则没有额度可补 —— 那本来也就是「还没刷」。
     *
     * @return 本次补上的额度（一定是 5 的倍数）
     * @throws SaveFormatException 今天还没有掉落记录，或今天已经领满
     */
    public long claimTodayDrop() throws SaveFormatException {
        Currencies before = currencies();
        String day = LocalDate.now().toString();
        Map<Object, Object> ledger = ledgerMap();
        Map<Object, Object> drops = asMap(ledger.get("drops"));
        if (!drops.containsKey(day)) {
            throw new SaveFormatException(
                    "存档里还没有今天（%s）的副本掉落记录。\n先进游戏副本刷一次、掉出通宝后再回来，"
                            .formatted(day)
                            + "就能把当天剩下的额度一次补满。");
        }
        long used = HellTongbao.usage(drops, asMap(ledger.get("pend"))).getOrDefault(day, 0L);
        long free = HellTongbao.freeOn(used);
        if (free <= 0) {
            throw new SaveFormatException(
                    "今天的副本额度已经领满（%d / %d），明天副本结算后再来。"
                            .formatted(used, HellTongbao.DAILY_CAP));
        }
        long after = used + free;
        if (!HellTongbao.dayAmountValid(after)) {
            throw new SaveFormatException(
                    "今天的掉落写进去会变成 %d，不符合游戏规则（上限 %d、%d 的倍数），已中止。"
                            .formatted(after, HellTongbao.DAILY_CAP, HellTongbao.UNIT));
        }
        document.patchInt(List.of("account_currencies", "tongbao_ledger", "drops", day), after);
        document.patchInt(List.of("account_currencies", "tongbao_ledger", "drop"),
                before.drop() + free);
        document.patchInt(List.of("account_currencies", "tongbao"), before.tongbao() + free);
        return free;
    }

    private Map<Object, Object> ledgerMap() {
        return asMap(asMap(document.payload().get("account_currencies")).get("tongbao_ledger"));
    }

    // ---------------------------------------------------------------- 角色加成（借鉴上帝之手）

    /** 玩家等级上限，来自 {@code PlayerStats.MAX_LEVEL}。 */
    public static final int MAX_LEVEL = 85;

    /** 装备背包上限：{@code BagManager.EQUIPMENT_PAGE_COUNT(30) × INVENTORY_PAGE_SIZE(25)}。 */
    public static final int EQUIPMENT_BAG_MAX = 750;

    /**
     * 副本难度解锁的天花板：{@code GameManager.DIFFICULTY_ORDER} 有 6 档
     * （简单/普通/困难/精英/英雄/史诗），游戏的「上帝之手」顶到 {@code size − 2}，
     * 即「英雄」档。这里照抄同一个值。
     */
    public static final int TOP_DUNGEON_DIFFICULTY = 4;

    /** 灵兽经验池上限，来自 {@code GameManager.SHENSHOU_EXP_POOL_MAX}（40 亿）。 */
    public static final long SHENSHOU_EXP_POOL_MAX = 4_000_000_000L;

    /**
     * 读取角色当前等级（{@code progression.level}）。
     *
     * @param slot 卡槽
     * @return 等级（至少 1）
     */
    public int characterLevel(int slot) {
        Map<Object, Object> progression = asMap(characterMap(slot).get("progression"));
        return (int) Math.max(1, asLong(progression.get("level")));
    }

    /**
     * 把角色直升到目标等级，技能点按游戏规则照常发放。
     *
     * <p>等价于「上帝之手 → 赐予经验」，但直接落到存档：
     * <ul>
     *   <li>{@code progression.level} 写成目标等级、当前经验清零（属性 HP/攻防都是
     *       按等级换算的运行时值，读档自动重算，不用改）；</li>
     *   <li>技能点完全照抄 {@code SkillManager.award_second_stage_level_points} 的规则：
     *       只有<b>已二阶进阶</b>且记录了发放进度的角色才补
     *       {@code 目标等级 − 已发放到等级} 点（每级 1 点），没进阶过的角色不发
     *       —— 和游戏里先练级后进阶的行为完全一致。</li>
     * </ul>
     *
     * @param slot   卡槽
     * @param target 目标等级（超过 85 按 85 处理）
     * @return 本次补发的技能点数
     * @throws SaveFormatException 目标不高于当前等级，或存档结构异常
     */
    public int setCharacterLevel(int slot, int target) throws SaveFormatException {
        Map<Object, Object> ch = characterMap(slot);
        Map<Object, Object> progression = asMap(ch.get("progression"));
        int current = (int) Math.max(1, asLong(progression.get("level")));
        if (target <= current) {
            throw new SaveFormatException("目标等级 Lv." + target + " 不高于当前 Lv." + current);
        }
        int level = Math.min(target, MAX_LEVEL);
        document.patchInt(List.of("characters", slot, "progression", "level"), level);
        if (progression.containsKey("exp")) {
            document.patchInt(List.of("characters", slot, "progression", "exp"), 0);
        }

        Map<Object, Object> skills = asMap(ch.get("skills"));
        boolean advanced = Boolean.TRUE.equals(skills.get("is_advanced"))
                || asLong(skills.get("is_advanced")) == 1;
        if (advanced && skills.containsKey("skill_points")
                && skills.containsKey("skill_points_awarded_through_level")) {
            long through = asLong(skills.get("skill_points_awarded_through_level"));
            if (level > through) {
                document.patchInt(List.of("characters", slot, "skills", "skill_points"),
                        asLong(skills.get("skill_points")) + (level - through));
                document.patchInt(
                        List.of("characters", slot, "skills", "skill_points_awarded_through_level"),
                        level);
                return (int) (level - through);
            }
        }
        return 0;
    }

    /**
     * 把该角色<b>已有记录</b>的副本难度全部顶到「英雄」档。
     *
     * <p>照抄游戏「上帝之手 → 解锁全部难度」的做法（{@code maxi(已有, size−2)}）。
     * 唯一的限制来自原地补丁：没打过的副本在存档里没有键，加不进去 ——
     * 那部分依旧要在游戏里正常解锁。
     *
     * @param slot 卡槽
     * @return 顶上去的副本数（0 表示没有可解锁的记录）
     * @throws SaveFormatException 字段写入失败
     */
    public int unlockDungeons(int slot) throws SaveFormatException {
        Map<Object, Object> diff = asMap(characterMap(slot).get("dungeon_difficulties"));
        int changed = 0;
        for (Object key : diff.keySet()) {
            if (asLong(diff.get(key)) < TOP_DUNGEON_DIFFICULTY) {
                document.patchInt(
                        List.of("characters", slot, "dungeon_difficulties", String.valueOf(key)),
                        TOP_DUNGEON_DIFFICULTY);
                changed++;
            }
        }
        return changed;
    }

    /**
     * 该角色是否已有灵兽经验池（在游戏里打开过灵兽面板就会有）。
     *
     * @param slot 卡槽
     * @return 有返回 true
     */
    public boolean hasShenshouPool(int slot) {
        return asMap(characterMap(slot).get("shenshou")).containsKey("exp_pool");
    }

    /**
     * 把灵兽经验池拉满（等价于「上帝之手 → 赐灵兽经验」，只是直接顶到上限 40 亿）。
     *
     * @param slot 卡槽
     * @return 拉满后的池子数值
     * @throws SaveFormatException 角色还没有经验池，或字段是 32 位写不进 40 亿
     */
    public long maxShenshouPool(int slot) throws SaveFormatException {
        Map<Object, Object> shenshou = asMap(characterMap(slot).get("shenshou"));
        if (!shenshou.containsKey("exp_pool")) {
            throw new SaveFormatException(
                    "这个角色还没有灵兽经验池。先在游戏里打开一次灵兽面板，再来拉满。");
        }
        try {
            document.patchInt(List.of("characters", slot, "shenshou", "exp_pool"),
                    SHENSHOU_EXP_POOL_MAX);
            return SHENSHOU_EXP_POOL_MAX;
        } catch (SaveFormatException e) {
            // 个别旧档把池子存成 32 位整数，装不下 40 亿：退一步顶满 int32（约 21.4 亿）
            document.patchInt(List.of("characters", slot, "shenshou", "exp_pool"),
                    Integer.MAX_VALUE);
            return Integer.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------ 掉落保底

    /** 保底规则表的懒加载单例（资源缺失时退化成空表，界面会提示无可用规则）。 */
    private static PityCatalog pityCatalog;

    private static synchronized PityCatalog pityCatalog() {
        if (pityCatalog == null) {
            try {
                pityCatalog = PityCatalog.loadDefault();
            } catch (IOException e) {
                pityCatalog = PityCatalog.empty();
            }
        }
        return pityCatalog;
    }

    /**
     * 一条掉落保底进度。
     *
     * @param section  所属存档字段（{@code drop_pity} / {@code jewelry_pity}）
     * @param key      存档字典里的键
     * @param itemId   物品 ID（规则未知时为 0）
     * @param name     物品名
     * @param scope    作用范围（难度名或「关卡 926」）
     * @param maxRolls 保底次数（规则未知时为 0）
     * @param current  当前已累计的连续未中次数
     * @param known    是否有可用的保底规则
     * @param present  存档里是否已经有这个计数键（没有的话写入时会新补一条）
     */
    public record PityStatus(String section, String key, int itemId, String name, String scope,
                             int maxRolls, long current, boolean known, boolean present) {

        /** @return 能推到的最激进值（保底次数 − 1）：到这里下一次击杀必掉 */
        public int target() {
            return known && maxRolls > 0 ? maxRolls - 1 : -1;
        }

        /** @return 是否已经处于「下一次必掉」状态 */
        public boolean ready() {
            return known && maxRolls > 0 && current >= maxRolls - 1;
        }

        /** @return 是否还值得推（有规则，且没到必掉；没打过的也会补一条计数） */
        public boolean fillable() {
            return known && maxRolls > 0 && current < maxRolls - 1;
        }

        /** @return 距离「下一次必掉」还差几次 */
        public long remain() {
            return known && maxRolls > 0 ? Math.max(0, (maxRolls - 1) - current) : -1;
        }
    }

    /**
     * 读出该角色所有掉落保底的当前进度。
     *
     * <p>只读：不修改任何字节。规则表覆盖 BOSS 材料、首饰、守护神关卡与小怪专属材料；
     * 存档里存在但规则表查不到上限的键也会列出来，标记 {@code known=false}，
     * 这类一律不参与写入。
     *
     * @param slot 卡槽
     * @return 保底进度列表（先 drop_pity 后 jewelry_pity，各自按规则表顺序，末尾附未知项）
     */
    public List<PityStatus> pityStatus(int slot) {
        Map<Object, Object> ch = characterMap(slot);
        List<PityStatus> out = new ArrayList<>();
        for (String section : List.of(PityCatalog.SECTION_DROP, PityCatalog.SECTION_JEWELRY)) {
            Map<String, Object> byKey = stringKeyedMap(ch.get(section));
            List<PityCatalog.Rule> rules = pityCatalog().rules(section);
            Set<String> known = new HashSet<>();
            for (PityCatalog.Rule rule : rules) {
                known.add(rule.key());
                boolean present = byKey.containsKey(rule.key());
                out.add(new PityStatus(section, rule.key(), rule.itemId(), rule.name(),
                        rule.scope(), rule.maxRolls(), asLong(byKey.get(rule.key())), true, present));
            }
            for (Map.Entry<String, Object> entry : byKey.entrySet()) {
                if (!known.contains(entry.getKey())) {
                    out.add(new PityStatus(section, entry.getKey(), 0,
                            "规则未知 #" + entry.getKey(), "—", 0,
                            asLong(entry.getValue()), false, true));
                }
            }
        }
        return out;
    }

    /**
     * 把掉落保底计数推到「保底次数 − 1」，让下一次击杀对应 BOSS 必掉。
     *
     * <p>游戏判定是 {@code 连续未中次数 + 1 >= 保底次数} 就必出、出了清零
     * （{@code globals/drop_pity.gd}），所以推到 {@code 保底次数 − 1} 即等价于
     * 「再打一次必给」，且这是正常玩家刷本可以达到的合法状态，数值守卫也不检查
     * 这个字段，不会触发任何核账。
     *
     * <p>存档里已经有这个计数键的直接改值；<b>还没打过的 BOSS 会新补一条计数</b>
     * （{@code drop_pity} / {@code jewelry_pity} 都是自描述字典，末尾追加
     * {@code 字符串键: 整数} 即可被游戏正常读出来）。规则未知的键一律跳过。
     *
     * @param slot 卡槽
     * @param keys 只推这些键（{@code null} 表示全部可推的）
     * @return 实际改动条数（改值 + 新增）
     * @throws SaveFormatException 字段写入失败
     */
    public int fillPity(int slot, Set<String> keys) throws SaveFormatException {
        Map<Object, Object> ch = characterMap(slot);
        int changed = 0;
        for (String section : List.of(PityCatalog.SECTION_DROP, PityCatalog.SECTION_JEWELRY)) {
            Map<String, Object> byKey = stringKeyedMap(ch.get(section));
            for (PityCatalog.Rule rule : pityCatalog().rules(section)) {
                if (rule.maxRolls() <= 0) {
                    continue;
                }
                if (keys != null && !keys.contains(rule.key())) {
                    continue;
                }
                long target = rule.maxRolls() - 1L;
                if (byKey.containsKey(rule.key())) {
                    if (asLong(byKey.get(rule.key())) >= target) {
                        continue;
                    }
                    document.patchInt(List.of("characters", slot, section, rule.key()), target);
                } else {
                    appendPityKey(slot, section, rule.key(), target);
                }
                changed++;
            }
        }
        return changed;
    }

    /** 伏魔塔（801）出货保底所在段：{@code misses} 连续空手次数、{@code claimed} 已领取奖励。 */
    public static final String FUMO_SECTION = "fumo_tower_rewards";

    /** 伏魔塔奖励保底次数（对应 {@code 801-伏魔塔/伏魔塔.gd} 的 FUMO_REWARD_PITY）。 */
    public static final int FUMO_PITY = 10;

    /**
     * 伏魔塔（801）的通关次数与出货保底状态。
     *
     * <p>游戏判定是 {@code 概率未中 且 misses + 1 < 10} 才算空手，否则必出；
     * 所以 {@code misses} 推到 9 就是「下一把通关必给一件伏魔套/首饰」。
     *
     * @param cleared 已通关次数（{@code fumo_tower.cleared}）
     * @param misses  连续空手次数
     * @param pity    保底次数（固定 10）
     * @param claimed 已领取过的奖励 ID（集满 10 件后游戏会自行重置）
     */
    public record FumoTowerStatus(long cleared, long misses, int pity, List<Long> claimed) {

        /** @return 是否已经处于「下一把必掉」状态 */
        public boolean ready() {
            return misses >= pity - 1;
        }

        /** @return 距离「下一把必掉」还差几次 */
        public long remain() {
            return Math.max(0, (pity - 1) - misses);
        }

        /** @return 是否还值得推 */
        public boolean fillable() {
            return misses < pity - 1;
        }
    }

    /**
     * 读出当前角色的伏魔塔（801）出货保底状态。
     *
     * <p>只读。段不存在时按「没打过、0 次空手」处理。
     *
     * @param slot 卡槽
     * @return 保底状态
     */
    public FumoTowerStatus fumoTowerStatus(int slot) {
        Map<Object, Object> ch = characterMap(slot);
        Map<String, Object> rewards = stringKeyedMap(ch.get(FUMO_SECTION));
        long misses = Math.max(0, asLong(rewards.get("misses")));
        long cleared = asLong(stringKeyedMap(ch.get("fumo_tower")).get("cleared"));
        List<Long> claimed = new ArrayList<>();
        if (rewards.get("claimed") instanceof List<?> list) {
            for (Object one : list) {
                claimed.add(asLong(one));
            }
        }
        return new FumoTowerStatus(cleared, misses, FUMO_PITY, claimed);
    }

    /**
     * 把伏魔塔（801）的空手计数推到 9，让下一把通关必掉一件伏魔套/首饰。
     *
     * <p>段不存在时整段新建（自描述字典，末尾补 {@code "misses": 9} 即可被游戏读到），
     * 已有计数直接原地改值。这是正常玩家刷塔能达到的合法状态，数值守卫不检查该字段。
     *
     * @param slot 卡槽
     * @return 实际改动条数（0 或 1）
     * @throws SaveFormatException 字段写入失败
     */
    public int fillFumoTowerPity(int slot) throws SaveFormatException {
        long target = FUMO_PITY - 1L;
        if (fumoTowerStatus(slot).misses() >= target) {
            return 0;
        }
        Map<String, Object> rewards = stringKeyedMap(characterMap(slot).get(FUMO_SECTION));
        if (rewards.containsKey("misses")) {
            document.patchInt(List.of("characters", slot, FUMO_SECTION, "misses"), target);
        } else {
            appendPityKey(slot, FUMO_SECTION, "misses", target);
        }
        return 1;
    }

    /** 霸王卷轴（霸王虎）保底所在段：{@code n} 累计通关次数、{@code seed} 当前保底周期的起点。 */
    public static final String BAWANG_SECTION = "bawanghu_pity";

    /** 霸王卷轴的道具 ID（用了才孵化成霸王虎）。 */
    public static final long BAWANG_ITEM = 1410010L;

    /**
     * 霸王卷轴的保底次数：连续 300 次没出就必出一卷（玩家确认的 1.0.8 规则）。
     *
     * <p>而且每个角色只能出一次，所以判定「是不是已经出过」不看计数，直接看物证：
     * 灵兽栏里有霸王虎，或者背包里还压着一张没用的卷轴。
     */
    public static final long BAWANG_PITY = 300;

    /**
     * 霸王卷轴（霸王虎）掉落保底状态。
     *
     * <p>{@code n} 是累计通关虚空炼狱的次数（实测与 {@code void_prison_records} 的场次吻合），
     * {@code seed} 是本轮保底周期的起点：出货后会被重置成当时的 {@code n}，
     * 所以 {@code n - seed} 就是「这一轮已经连续多少次没出」。
     * 例如 slot0 承一 n=39 / seed=39（刚出过，已孵出霸王虎），
     * slot3 小兔子 n=112 / seed=79（没出过，这一轮只走了 33 次）。
     *
     * <p>{@code seed} 不是「出过货」的标记——没出过的角色也会有非 0 的起点值。
     * 是否已经出过只认 {@link #owned()}。
     *
     * @param n       累计通关次数
     * @param seed    本轮保底周期起点
     * @param present 存档里是否已经有这个段
     * @param owned   是否已经拿到过霸王卷轴／霸王虎（每角色只能出一次）
     */
    public record BawanghuPity(long n, long seed, boolean present, boolean owned) {

        /** @return 这一轮已经连续多少次没出霸王卷轴 */
        public long sinceLast() {
            return Math.max(0, n - seed);
        }

        /** @return 触发保底需要的累计次数（起点 + 300） */
        public long target() {
            return seed + BAWANG_PITY;
        }

        /** @return 还能不能靠推计数把保底顶出来 */
        public boolean fillable() {
            return !owned && n < target();
        }
    }

    /**
     * 读霸王卷轴的保底状态（只读）。
     *
     * @param slot 卡槽
     * @return 保底状态；没打过虚空炼狱的角色 {@code present=false}
     */
    public BawanghuPity bawanghuPity(int slot) {
        Map<String, Object> raw = stringKeyedMap(characterMap(slot).get(BAWANG_SECTION));
        boolean owned = bawanghuOwned(slot);
        if (raw.isEmpty()) {
            return new BawanghuPity(0, 0, false, owned);
        }
        return new BawanghuPity(asLong(raw.get("n")), asLong(raw.get("seed")), true, owned);
    }

    /**
     * 这个角色是否已经拿到过霸王卷轴（灵兽栏有霸王虎，或背包里还有一张没用的卷轴）。
     *
     * <p>用它而不是 {@code bawanghu_pity.seed} 判定「是否已出过」：小兔子 n=112/seed=79
     * 从没出过卷轴，seed 只是本轮周期的起点，拿它当出货标记会误伤。
     *
     * @param slot 卡槽
     * @return 已拥有霸王虎或持有霸王卷轴
     */
    public boolean bawanghuOwned(int slot) {
        Map<String, Object> character = stringKeyedMap(characterMap(slot));
        Map<String, Object> pets = stringKeyedMap(
                stringKeyedMap(character.get("shenshou")).get("pets"));
        if (pets.containsKey("霸王虎")) {
            return true;
        }
        Map<String, Object> inventory = stringKeyedMap(character.get("inventory"));
        for (String category : new String[]{"items", "materials", "equipment"}) {
            for (Object raw : asList(inventory.get(category))) {
                Map<String, Object> item = stringKeyedMap(raw);
                if (asLong(item.get("id")) == BAWANG_ITEM && asLong(item.get("count")) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 把霸王卷轴的累计次数推到「本轮起点 + 300」，让下一次虚空炼狱通关必掉霸王卷轴。
     *
     * <p>只动 {@code n}，{@code seed} 保持不动。已经拿到过卷轴/霸王虎的角色直接返回 0：
     * 1.0.8 的规则是每个角色只能出一次，推了也不会给第二张。
     *
     * <p>段不存在时整段新建：{@code seed} 写 0、{@code n} 写 300，等价于
     * 「从头刷了 300 把都没出」，游戏读到就按保底给。
     *
     * @param slot 卡槽
     * @return 实际改动条数（0 或 1）
     * @throws SaveFormatException 结构异常
     */
    public int fillBawanghuPity(int slot) throws SaveFormatException {
        BawanghuPity now = bawanghuPity(slot);
        if (now.owned()) {
            return 0;
        }
        long target = Math.max(now.target(), now.n() + 1L);
        if (now.present() && now.n() >= target) {
            return 0;
        }
        if (now.present()) {
            document.patchInt(List.of("characters", slot, BAWANG_SECTION, "n"), target);
        } else {
            appendPityKey(slot, BAWANG_SECTION, "seed", 0L);
            appendPityKey(slot, BAWANG_SECTION, "n", target);
        }
        return 1;
    }

    /**
     * 给角色补一条掉落保底计数：必要时先建出整个段（例如首饰保底段一开始并不存在）。
     *
     * <p>每次插入都要重新定位：{@link SaveDocument#splice} 会让后面的字节整体后移，
     * 旧偏移立刻失效。
     *
     * @param slot    卡槽
     * @param section 段名
     * @param key     计数键
     * @param value   初始值
     * @throws SaveFormatException 结构异常
     */
    private void appendPityKey(int slot, String section, String key, long value)
            throws SaveFormatException {
        VariantNavigator navigator = document.navigator();
        int characterOffset = navigator.locate(document.payloadOffset(), List.of("characters", slot));
        boolean hasSection = navigator.dictEntries(characterOffset).stream()
                .anyMatch(entry -> section.equals(String.valueOf(entry.key())));
        if (!hasSection) {
            appendDictEmptyDict(navigator, characterOffset, section);
            navigator = document.navigator();
        }
        int sectionOffset = navigator.locate(document.payloadOffset(),
                List.of("characters", slot, section));
        appendDictInt(navigator, sectionOffset, key, value);
    }

    /** 空字典的 Variant 编码：类型码 27 + 元素个数 0，键值都不带类型标志。 */
    private static final byte[] EMPTY_DICT = {27, 0, 0, 0, 0, 0, 0, 0};

    /**
     * 向字典末尾追加 {@code 字符串键: 空字典}。
     *
     * <p>与 {@link #appendDictInt} 同一套路，只是值换成一个空字典——
     * 用于新建 {@code jewelry_pity} 这种整段缺失的字段。
     */
    private void appendDictEmptyDict(VariantNavigator navigator, int dictOffset, String key)
            throws SaveFormatException {
        int countOffset = dictCountOffset(navigator, dictOffset);
        int rawCount = navigator.readU32(countOffset);
        byte[] keyBytes = ItemTemplate.encodeString(key);
        byte[] pair = new byte[keyBytes.length + EMPTY_DICT.length];
        System.arraycopy(keyBytes, 0, pair, 0, keyBytes.length);
        System.arraycopy(EMPTY_DICT, 0, pair, keyBytes.length, EMPTY_DICT.length);

        document.splice(navigator.walkToEnd(dictOffset), pair);
        document.refreshPlainLength();
        document.navigator().writeU32(countOffset,
                (rawCount & 0x80000000) | ((rawCount & 0x7FFFFFFF) + 1));
        document.rebuildEnvelope();
    }

    /** 把当前角色所有能推的保底一次推满（含伏魔塔 801 与霸王卷轴）。 */
    public int fillAllPity(int slot) throws SaveFormatException {
        return fillPity(slot, null) + fillFumoTowerPity(slot) + fillBawanghuPity(slot);
    }

    /** 统计当前角色还有多少条可以推满（界面用来显示按钮是否可点）。 */
    public int pityPending(int slot) {
        int pending = 0;
        for (PityStatus status : pityStatus(slot)) {
            if (status.fillable()) {
                pending++;
            }
        }
        if (fumoTowerStatus(slot).fillable()) {
            pending++;
        }
        if (bawanghuPity(slot).fillable()) {
            pending++;
        }
        return pending;
    }

    /** 把 Variant 解码出来的字典转成「字符串键 → 值」的视图（键统一按字符串比较）。 */
    private static Map<String, Object> stringKeyedMap(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private Map<Object, Object> characterMap(int slot) {
        List<Object> slots = asList(document.payload().get("characters"));
        if (slot < 0 || slot >= slots.size()) {
            return Map.of();
        }
        return asMap(slots.get(slot));
    }

    /**
     * 当前存档的签到天数是否已经超过「今天还能签到」的上限。
     *
     * <p>游戏签到会把「上次签到日期」改成当天，上限当场按今天重算。天数一旦超限，
     * 点签到就会被存档层拒绝并返回 {@code tongbao_ledger}。这个方法用来在界面
     * 提前把这件事说清楚（存档本身能载入、能买东西，只是签到这条路走不通）。
     *
     * @return 超出的天数（0 表示今天签到没问题）
     */
    public int signinOverflowDays() {
        Currencies c = currencies();
        return SigninLedger.overflowDays(c.signinTotal(), c.lastSigninDate(), LocalDate.now());
    }

    /**
     * 直接把累计签到天数顶到指定值（用于解锁按签到天数开放的商城装备）。
     *
     * <p><b>天数会被压到今天可达的上限以内</b>：写未来日期虽然能通过载入核对，
     * 但玩家一点签到，日期被改成今天、上限塌陷，立刻报 {@code tongbao_ledger}。
     * 所以这里宁可截断，也不写「看着合法、一签到就炸」的存档。
     *
     * @param days   期望累计天数
     * @param target 期望余额（超出该天数签到所得的部分会被截断）
     * @return 实际执行的方案
     * @throws SaveFormatException 存档结构异常
     */
    public TongbaoPlan applySigninDays(int days, long target) throws SaveFormatException {
        return applyPlan(SigninLedger.planDays(days, target, LocalDate.now()));
    }

    /**
     * 把签到天数与日期整理成「账本干净、今天还能签到」的状态。
     *
     * <p>用于修那些「日期被写到未来、载入能过但一点签到就报 {@code tongbao_ledger}」的存档：
     * <ul>
     *   <li>天数压到 {@code 今天可达上限 − 1}，给今天留出一次签到的空间；</li>
     *   <li>日期退回该天数对应的那一天（不晚于昨天），不再指向未来；</li>
     *   <li>余额按 {@code 签到所得 + 掉落 − 花费} 重算，花费保留原值但不低于购买记录。</li>
     * </ul>
     *
     * @return 实际写入的方案
     * @throws SaveFormatException 结构异常
     */
    public TongbaoPlan compactSignin() throws SaveFormatException {
        Currencies before = currencies();
        LocalDate today = LocalDate.now();
        int cap = Math.max(1, SigninLedger.capToday(today));
        int oldTotal = before.signinTotal();
        if (oldTotal <= 0) {
            // 从没签到过的存档没有可整理的东西
            return new TongbaoPlan(0, 0, 0, before.spent(), before.tongbao(),
                    before.lastSigninDate(), cap, false, before.tongbao(), false, false, 0L, 0L, 0);
        }
        // 留一天：今天还能点一次签到，改完当场就能验证账本是不是真的修好了
        int total = Math.min(oldTotal, Math.max(1, cap - 1));
        int cycle = (total - 1) % SigninLedger.CYCLE_DAYS + 1;
        long newEarned = SigninLedger.earned(total);
        long income = newEarned + before.admin() + before.god() + before.drop();
        long spent = Math.max(before.buySum(), Math.min(before.spent(), income));
        long balance = Math.max(0, income - spent);

        // 日期：能撑住 total 的最早一天是起点 + total − 1；最晚只到昨天（今天留给游戏签到）
        LocalDate earliest = SigninLedger.FLOOR_DATE.plusDays(total - 1L);
        LocalDate latest = today.minusDays(1);
        LocalDate lastDate = earliest;
        LocalDate origin = HellTongbao.parse(before.lastSigninDate());
        if (origin != null) {
            lastDate = origin.isAfter(latest) ? latest : origin;
            if (lastDate.isBefore(earliest)) {
                lastDate = earliest;
            }
        }

        document.patchInt(List.of("daily_signin", "total"), total);
        document.patchInt(List.of("daily_signin", "cycle_day"), cycle);
        if (before.lastSigninDate().length() == 10) {
            document.patchString(List.of("daily_signin", "last_date"), lastDate.toString());
        }
        document.patchInt(List.of("account_currencies", "tongbao"), balance);
        document.patchInt(List.of("account_currencies", "tongbao_ledger", "spent"), spent);

        return new TongbaoPlan(total, cycle, newEarned, spent, balance, lastDate.toString(), cap,
                oldTotal > total, oldTotal, false, false, 0L, 0L, 0);
    }

    /**
     * 把一份账本方案写进存档：通宝余额、签到天数/轮次/日期、购买记录金额。
     *
     * <p><b>关键：花费不是照抄方案里的数字，而是按「总入账守恒」现算。</b>
     *
     * <p>1.0.7 起账本多了「副本掉落」{@code drop}（以及 {@code god}），等式变成
     * {@code 余额 = 签到所得 + 管理员 + drop + god − 花费}。如果仍按
     * {@code 花费 = 签到所得 − 余额} 来写，那么 drop 有多少，账本就少算多少 —— 实测
     * drop=20 时改一次通宝，账本总额就凭空少 20，游戏会判账本不平。
     *
     * <p>这里改用一个不依赖公式构成的办法：游戏自己保证「余额 = 总入账 − 花费」，
     * 于是 {@code 总入账 = 余额 + 花费} 可以反推出来。我们只改变「签到所得」这一项
     * （由累计天数决定），其余入账项（drop / god / admin）原样不动，所以：
     * <pre>
     * 新总入账 = 旧总入账 + (新天数签到所得 − 旧天数签到所得)
     * 新花费   = 新总入账 − 目标余额
     * </pre>
     * 这样不管官方以后往等式里再加几项，只要「余额 + 花费」这个总量是守恒的，就不会算错。
     *
     * @param plan 方案
     * @return 实际执行的方案（余额被截断、日期字段缺失时会带上相应标记）
     * @throws SaveFormatException 存档结构异常
     */
    private TongbaoPlan applyPlan(TongbaoPlan plan) throws SaveFormatException {
        Currencies before = currencies();

        long incomeBefore = before.totalIncome();
        long earnedDelta = plan.earned() - SigninLedger.earned(before.signinTotal());
        long incomeAfter = Math.max(0, incomeBefore + earnedDelta);
        // 花费不能为负，所以余额的上限就是总入账；超了只能截断
        long balance = Math.max(0, Math.min(plan.balance(), incomeAfter));
        long spent = incomeAfter - balance;
        boolean capped = plan.capped() || balance < plan.requested();

        TongbaoPlan result = plan.withLedger(spent, balance, capped);

        document.patchInt(List.of("account_currencies", "tongbao"), balance);
        document.patchInt(List.of("account_currencies", "tongbao_ledger", "spent"), spent);
        document.patchInt(List.of("daily_signin", "total"), result.total());
        document.patchInt(List.of("daily_signin", "cycle_day"), result.cycleDay());

        // 购买记录金额清零（保留条目本身，避免触发"商城装备数 > 购买记录"的封印规则）
        List<Object> buys = asList(asMap(asMap(document.payload().get("account_currencies"))
                .get("tongbao_ledger")).get("buys"));
        for (int i = 0; i < buys.size(); i++) {
            if (asMap(buys.get(i)).containsKey("p")) {
                document.patchInt(
                        List.of("account_currencies", "tongbao_ledger", "buys", i, "p"), 0);
            }
        }

        if (before.lastSigninDate().length() == result.lastDate().length()) {
            document.patchString(List.of("daily_signin", "last_date"), result.lastDate());
        } else if (before.lastSigninDate().isEmpty()) {
            // 从未签到过的存档没有这个字段，硬塞一个长度不等的字符串会破坏结构。
            // 这种情况不阻断修改：其余账本字段照写，只是日期留着，用户进游戏签到一次即可恢复。
            result = result.withDateSkipped(true);
        } else {
            throw new SaveFormatException(
                    "last_date 字段内容异常（%s），请先进游戏签到一次再改".formatted(before.lastSigninDate()));
        }
        return result;
    }

    /**
     * 向背包添加物品；可堆叠区已拥有则累加数量，装备区每件独立追加。
     *
     * <p>装备走的是游戏自己的记账结构：{@code inventory.equipment} 数组里追加一条
     * {@code {uid, id}}（与副本掉落落包完全同款），<b>不带</b> {@code from_mall} 标志 ——
     * 核账只统计带该标志的商城装备件数，所以新装备对账本完全隐形，不会触发
     * 「商城装备件数 &gt; 购买记录」。
     *
     * @param slot    卡槽
     * @param itemId  物品 ID
     * @param count   数量（装备为追加件数）
     * @param section 目标分区（null 时按目录自动判断）
     * @return 添加结果
     * @throws SaveFormatException 结构异常
     */
    public AddResult addItem(int slot, int itemId, long count, String section) throws SaveFormatException {
        return addItem(slot, itemId, count, section, 0);
    }

    /**
     * 同上，但装备可以带初始强化等级。
     *
     * <p>强化等级只在装备分区生效：追加条目后按品质上限收敛写入
     * {@code strengthen_level}（条目没有该字段就补键）。首饰与橙色装备
     * 游戏本身不允许强化，传了也会被忽略；道具/材料一律忽略。
     *
     * @param slot       卡槽
     * @param itemId     物品 ID
     * @param count      数量（装备为追加件数）
     * @param section    目标分区（null 时按目录自动判断）
     * @param strengthen 初始强化等级（0 表示不强化）
     * @return 添加结果
     * @throws SaveFormatException 结构异常
     */
    public AddResult addItem(int slot, int itemId, long count, String section, int strengthen)
            throws SaveFormatException {
        if (count <= 0) {
            throw new SaveFormatException("添加数量必须大于 0");
        }
        String target = section == null ? catalog.section(itemId) : section;

        List<ItemView> existing = items(slot, target);
        if (ItemCatalog.SECTION_EQUIPMENT.equals(target)
                && existing.size() + count > EQUIPMENT_BAG_MAX) {
            throw new SaveFormatException(
                    "装备背包放不下：现有 %d 件，再加 %d 件会超过游戏上限 %d（30 页 × 每页 25）。"
                            .formatted(existing.size(), count, EQUIPMENT_BAG_MAX));
        }
        // 装备一条就是一件、没有 count 字段：数量 N 就是追加 N 条独立条目
        if (ItemCatalog.SECTION_EQUIPMENT.equals(target)) {
            int size = existing.size();
            for (long i = 0; i < count; i++) {
                size = appendEntry(slot, target, itemId, count);
            }
            // 初始强化：只对刚追加的最后一条生效，并按品质上限收敛
            if (strengthen > 0 && catalog.canStrengthen(itemId)) {
                int level = Math.min(strengthen, catalog.strengthenCap(itemId));
                ItemView fresh = new ItemView(size - 1, itemId, catalog.displayName(itemId),
                        1, ItemCatalog.SECTION_EQUIPMENT, 0, null);
                setStrengthenLevel(slot, fresh, level);
            }
            return new AddResult(itemId, catalog.displayName(itemId), target, count, size, false);
        }
        for (ItemView item : existing) {
            if (item.id() == itemId) {
                long newCount = item.count() + count;
                setItemCount(slot, target, item.index(), newCount);
                return new AddResult(itemId, item.name(), target, newCount, existing.size(), true);
            }
        }
        checkNewMaterialLimit(itemId, count);
        int size = appendEntry(slot, target, itemId, count);
        return new AddResult(itemId, catalog.displayName(itemId), target, count, size, false);
    }

    /**
     * 往背包里<b>新增</b>一条物品前的材料审计校验。
     *
     * <p>已存在的条目走 {@link #setItemCount}，那条路自带校验；这里是给「新建条目」
     * 用的，判断依据是账号内当前合计 + 本次要加的数量有没有超过游戏算出的上限。
     *
     * @param itemId 物品 ID
     * @param count  本次新增数量
     * @throws SaveFormatException 超出审计上限
     */
    private void checkNewMaterialLimit(int itemId, long count) throws SaveFormatException {
        // 先过单格堆叠上限：这是 1.1.1 的硬规则，与账号级审计无关
        checkStackLimit(itemId, count);
        MaterialCap cap = materialCaps().get(itemId);
        if (cap == null || !cap.limited()) {
            return;
        }
        long total = materialTotal(itemId);
        long max = Math.max(0, cap.cap() - total);
        if (count > max) {
            throw new SaveFormatException(
                    "超出游戏认定的材料上限：%s 账号内最多还能放 %d 个（现有 %d / 上限 %d）。"
                            .formatted(catalog.displayName(itemId), max, total, cap.cap())
                            + "超出的部分登录时会被直接回收。");
        }
    }

    /**
     * 向某分区数组末尾追加一个条目（拿数组第一个元素当模板，替换 uid / id / count）。
     *
     * <p>这是字节级的「手术插入」：数组尾部追加元素字节、元素计数 +1、重建信封，
     * 数组之后的字节整体后移但内容不变，游戏按顺序解析不受影响。
     *
     * @param slot    卡槽
     * @param target  背包分区
     * @param itemId  物品 ID
     * @param count   条目内的数量字段（装备没有该字段，值被忽略）
     * @return 追加后的分区条目数
     * @throws SaveFormatException 分区不存在或没有模板条目
     */
    private int appendEntry(int slot, String target, int itemId, long count) throws SaveFormatException {
        VariantNavigator navigator = document.navigator();
        int arrayOffset = navigator.locate(document.payloadOffset(),
                List.of("characters", slot, "inventory", target));
        List<VariantNavigator.ArrayEntry> entries = navigator.arrayEntries(arrayOffset);
        if (entries.isEmpty()) {
            throw new SaveFormatException(
                    "该角色的「%s」背包是空的，没有可复制的模板条目。\n"
                            .formatted(target)
                            + "请先进游戏让这个角色获得至少一件该类物品。");
        }
        // 装备从零编码极简条目（同游戏副本掉落），避免继承模板的强化/耐久；
        // 道具与材料字段多，仍然克隆现有条目
        byte[] element = ItemCatalog.SECTION_EQUIPMENT.equals(target)
                ? ItemTemplate.newEquipment(ItemTemplate.newUid(), itemId)
                : ItemTemplate.cloneWith(navigator, entries.get(0),
                        ItemTemplate.newUid(), itemId, count);

        int insertAt = navigator.walkToEnd(arrayOffset);
        int countOffset = countFieldOffset(navigator, arrayOffset);
        int rawCount = navigator.readU32(countOffset);

        document.splice(insertAt, element);
        document.refreshPlainLength();
        document.navigator().writeU32(countOffset,
                (rawCount & 0x80000000) | ((rawCount & 0x7FFFFFFF) + 1));
        // 元素个数改完才能重新解析信封，否则数组仍按旧长度遍历，结构解析会错位
        document.rebuildEnvelope();
        return entries.size() + 1;
    }

    /**
     * 重算签名、备份原档并写回磁盘。
     *
     * @return 保存结果
     * @throws IOException         读写失败
     * @throws SaveFormatException 签名或结构异常
     */
    public SaveOutcome save() throws IOException, SaveFormatException {
        ensureSchemaSupported();
        // 1.1.2 起存档 header 里有 ledger_snap（账本快照封条）和 transaction_id，
        // 它们与 generation 绑定（bind = profile_id|generation|transaction_id）。
        // 若代数 +1，快照封条对不上新代数，登录会被「账本快照」拒读，表现为
        // “存档与游戏封存的记录对不上，不能读取”。
        //
        // 1.1.2 的主档快速通道只要 save.dat 可读且代数不旧于水位（g >= M）就采纳，
        // 不再要求 hash 与水位一致；进游戏后由游戏自己 flush 时把代数、水位、
        // 账本快照一起推进。所以这里必须保持代数不变。
        boolean bump = false;
        // 写入前先把「改动之前」的完整状态做一次全量快照（存档原档 + 水位三处镜像
        // + 机器封禁 + 注册表 Cache 键），收进 backup/<时间戳>/ 文件夹。账本与封禁
        // 记录动不了，但能整份备份/还原：万一这次写入被游戏拒收，成对放回去就能
        // 回滚到上一个双方都认可的状态，不用等 24 小时恢复冷却。
        WatermarkSnapshot.snapshot(path);
        // 1.1.2 的账本快照是 native 签名、离线改不了，直接改经济段会被「snap」封禁。
        // 这里把存档 header 里的 ledger_snap 摘掉，再把水位 snap_g 归零，游戏会把这份
        // 档当成老档走迁移，进游戏后由它自己重建快照并承认修改。
        document.stripLedgerSnap();
        long generation = document.refreshSignature(bump);
        Path backup = document.saveWithBackup(path);
        boolean verified = SaveDocument.verify(path, key);
        String newHash = sha256Hex(Files.readAllBytes(path));
        try {
            WatermarkForge.resetSnapshot(path, newHash);
        } catch (Exception e) {
            throw new SaveFormatException(
                    "存档已写入，但重置账本快照标记失败：" + e.getMessage()
                            + "\n请用「还原备份」回到修改前的状态后重试。");
        }
        // 机器封禁记录 XWDL3 与存档、水位是两套独立状态。只重签水位不清它，
        // 下一次开档仍会命中 machine_seal_all() 被写成 x8，所以每次写档后一并清掉。
        MachineSeal.clear();
        // 注意：这里**故意不动** save.dat.bak。
        // 它保留着上一份「游戏亲手写的原档」，是写入万一被拒时的安全网：
        // 主档读不出来时，游戏会拿 .bak 比对账本指纹命中 R3a，照常进游戏，
        // 只是这次修改被无视——绝不会再出现「存档损坏、进不去」。
        return new SaveOutcome(generation, backup, verified, bump);
    }

    /** 计算文件 SHA-256 的 64 位十六进制小写串。 */
    private static String sha256Hex(byte[] data) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IOException("SHA-256 计算失败", e);
        }
    }

    /**
     * 写盘前的结构版本护栏。
     *
     * <p>游戏每次大更新都可能调整存档字段布局。一旦 {@code schema_version} 比修改器
     * 认识的更新，继续按旧布局写就有把存档写坏的风险，所以这里直接拦下，
     * 宁可这次不改，也不要写出一个游戏读不动的存档。
     *
     * @throws SaveFormatException 结构版本超出支持范围
     */
    private void ensureSchemaSupported() throws SaveFormatException {
        SaveMeta meta = meta();
        if (!meta.schemaSupported()) {
            throw new SaveFormatException(
                    ("存档结构是 v%d，修改器只认识 v%d —— 游戏大概刚更新过。\n"
                            + "为避免写坏存档，本次没有写入任何内容。\n"
                            + "建议先正常进游戏确认存档无损，再来更新修改器。")
                            .formatted(meta.schemaVersion(), SaveMeta.SUPPORTED_SCHEMA));
        }
    }

    /**
     * 校验磁盘上的存档签名是否自洽。
     *
     * @return 通过返回 true
     * @throws IOException         读盘失败
     * @throws SaveFormatException 解密失败
     */
    public boolean verify() throws IOException, SaveFormatException {
        return SaveDocument.verify(path, key);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 数组元素个数所在的偏移（头部 + 元素类型描述之后）。
     */
    private static int countFieldOffset(VariantNavigator navigator, int arrayOffset) {
        int header = navigator.readU32(arrayOffset);
        int kind = (header >>> 16) & 3;
        int afterHeader = arrayOffset + VariantSpan.HEADER_SIZE;
        return navigator.skipContainerType(kind, afterHeader);
    }

    private CharacterView toView(int slot, Map<Object, Object> character) {
        Map<Object, Object> identity = asMap(character.get("identity"));
        Map<Object, Object> progression = asMap(character.get("progression"));
        // 装备页签 = 身上穿的（带【穿戴】标记）+ 背包里的，都能改强化
        List<ItemView> equipment = new ArrayList<>(wornEquipment(slot));
        equipment.addAll(items(slot, ItemCatalog.SECTION_EQUIPMENT));
        return new CharacterView(
                slot,
                asString(identity.get("name")),
                asString(identity.get("role_id")),
                asLong(progression.get("level")),
                asLong(progression.get("kungfu_bean")),
                items(slot, ItemCatalog.SECTION_ITEMS),
                items(slot, ItemCatalog.SECTION_MATERIALS),
                equipment);
    }

    /**
     * 读取角色身上穿戴的装备（{@code equipment} 按部位组织的字典）。
     *
     * @param slot 卡槽
     * @return 各部位装备；部位名写进 {@code part}，名称加【穿戴】前缀
     */
    public List<ItemView> wornEquipment(int slot) {
        List<Object> slots = asList(document.payload().get("characters"));
        if (slot < 0 || slot >= slots.size()) {
            return List.of();
        }
        Map<Object, Object> worn = asMap(asMap(slots.get(slot)).get("equipment"));
        List<ItemView> out = new ArrayList<>(worn.size());
        for (Map.Entry<Object, Object> e : worn.entrySet()) {
            Map<Object, Object> item = asMap(e.getValue());
            int id = (int) asLong(item.get("id"));
            String part = String.valueOf(e.getKey());
            out.add(new ItemView(-1, id, "【穿戴】" + catalog.displayName(id), 1,
                    ItemCatalog.SECTION_EQUIPMENT,
                    asLong(item.get("strengthen_level")), part));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<Object, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }
}
