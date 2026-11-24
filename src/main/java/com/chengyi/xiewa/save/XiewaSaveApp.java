package com.chengyi.xiewa.save;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.chengyi.xiewa.save.cli.SaveLocator;
import com.chengyi.xiewa.save.core.SaveFormatException;
import com.chengyi.xiewa.save.keys.SaveKeys;
import com.chengyi.xiewa.save.model.Currencies;
import com.chengyi.xiewa.save.model.ItemCatalog;
import com.chengyi.xiewa.save.model.SaveDocument;
import com.chengyi.xiewa.save.model.SaveOutcome;
import com.chengyi.xiewa.save.model.SaveService;
import com.chengyi.xiewa.save.model.TongbaoPlan;

/**
 * 命令行入口。
 *
 * <p>用法：
 * <pre>
 *   xiewa-save info                     查看存档概览（默认命令）
 *   xiewa-save verify                   校验签名
 *   xiewa-save get account_currencies.tongbao
 *   xiewa-save list                     列出全部账号存档
 * </pre>
 *
 * <p>末尾可以附加存档路径，省略时自动取最近修改的那份。
 */
public final class XiewaSaveApp {

    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 1;
    private static final int EXIT_ERROR = 2;

    /** {@code info} 里仓库最多列几条（仓库常有上百条，全列会淹没概览）。 */
    private static final int WAREHOUSE_PREVIEW = 10;

    private XiewaSaveApp() {
        throw new AssertionError("入口类不允许实例化");
    }

    /**
     * 程序入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 错误信息也走 UTF-8：否则控制台里的中文报错会变成一堆问号。
        // 跟随 stdout 的字符集，用户用 -Dstdout.encoding=UTF-8 启动时两边一致。
        System.setErr(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, System.out.charset()));
        int code;
        try {
            code = dispatch(args);
        } catch (SaveFormatException e) {
            System.err.println("[错误] " + e.getMessage());
            code = EXIT_ERROR;
        } catch (IOException e) {
            System.err.println("[IO 错误] " + e.getMessage());
            code = EXIT_ERROR;
        } catch (RuntimeException e) {
            System.err.println("[内部错误] " + e);
            e.printStackTrace();
            code = EXIT_ERROR;
        }
        if (code != EXIT_OK) {
            System.exit(code);
        }
    }

    /**
     * 命令分发。第一个参数是命令名，其余参数原样交给各命令自行解析。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 存档或密钥异常
     */
    private static int dispatch(String[] args) throws IOException, SaveFormatException {
        String command = args.length == 0 ? "info" : args[0];
        return switch (command) {
            case "info" -> info(resolveSave(args, 1));
            case "verify" -> verify(resolveSave(args, 1));
            case "list" -> list();
            case "get" -> get(args);
            case "set" -> set(args);
            case "keys" -> keys(resolveSave(args, 1));
            case "harvest" -> harvest();
            case "bean" -> bean(args);
            case "demon" -> demon(args);
            case "tongbao" -> tongbao(args);
            case "signin" -> signin(args);
            case "repair-signin" -> repairSignin(args);
            case "item" -> item(args);
            case "itemid" -> itemById(args);
            case "all" -> all(args);
            case "add" -> add(args);
            case "-h", "--help", "help" -> usage();
            default -> {
                System.err.println("未知命令：" + command);
                yield usage();
            }
        };
    }

    // ---------------------------------------------------------------- 命令

    /**
     * 一份已解开的存档：文档对象 + 真正用得上的那把密钥。
     *
     * @param document 已解密的文档
     * @param key      解开它的密钥（后续签名自检要用）
     */
    private record Opened(SaveDocument document, byte[] key) {
    }

    /**
     * 按候选密钥逐个试解，返回第一个解得开的。
     *
     * <p>与 {@code SaveService.open} 用的是同一套策略：先本机 DPAPI，
     * 再 keys.json 里同账号的，最后 keys.json 里其他账号的。
     *
     * @param save 存档路径
     * @return 解开的结果
     * @throws IOException         读盘失败
     * @throws SaveFormatException 全部候选都解不开
     */
    private static Opened loadTrying(Path save) throws IOException, SaveFormatException {
        List<SaveKeys.Resolution> candidates = SaveKeys.candidates(save);
        if (candidates.isEmpty()) {
            candidates = List.of(SaveKeys.resolve(save));
        }
        SaveFormatException last = null;
        for (SaveKeys.Resolution candidate : candidates) {
            try {
                SaveDocument document = SaveDocument.load(save, candidate.key());
                SaveKeys.remember(save, candidate);
                return new Opened(document, candidate.key());
            } catch (SaveFormatException e) {
                last = e;
            }
        }
        throw last == null ? new SaveFormatException("无法解开存档：" + save) : last;
    }

    /**
     * {@code info}：打印账号、代数、密钥来源、货币、签到与角色概览。
     *
     * @param save 存档路径
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 解密或结构异常
     */
    private static int info(Path save) throws IOException, SaveFormatException {
        Opened opened = loadTrying(save);
        SaveDocument document = opened.document();
        SaveKeys.Resolution resolved = SaveKeys.resolve(save);
        String profileId = SaveLocator.profileIdOf(save);
        byte[] key = opened.key();

        Map<Object, Object> payload = document.payload();

        System.out.println("存档     : " + save.toAbsolutePath());
        System.out.println("账号     : " + profileId);
        System.out.println("代数     : " + document.generation());
        System.out.println("签名自检 : " + (SaveDocument.verify(save, key) ? "通过" : "失败"));
        System.out.println("密钥来源 : " + resolved.source().label() + " — " + resolved.detail());

        // 1.0.7 起存档自带版本信息与反作弊状态位，直接摊开给用户看
        Map<Object, Object> version = asMap(payload.get("save_version"));
        if (!version.isEmpty()) {
            System.out.printf("游戏版本 : %s (build %d) · 结构 v%d%n",
                    asString(version.get("last_client_version")),
                    asLong(version.get("last_client_build")),
                    asLong(payload.get("schema_version")));
        }
        Map<Object, Object> anti = asMap(payload.get("anti_ce"));
        if (!anti.isEmpty()) {
            boolean sealed = Boolean.TRUE.equals(anti.get("sealed"));
            System.out.printf("反作弊   : %s（命中 %d 次 关闭 %d 次）%n",
                    sealed ? "已标记" : "未触发",
                    asLong(anti.get("tool_hits")),
                    asLong(anti.get("closes")));
        }

        Map<Object, Object> currencies = asMap(payload.get("account_currencies"));
        if (!currencies.isEmpty()) {
            System.out.println();
            System.out.println("== 账户货币 ==");
            System.out.printf("  通宝     : %d%n", asLong(currencies.get("tongbao")));
            System.out.printf("  魔点     : %d%n", asLong(currencies.get("demon_points")));
            Map<Object, Object> ledger = asMap(currencies.get("tongbao_ledger"));
            if (!ledger.isEmpty()) {
                System.out.printf("  账本     : 花费=%d 管理员=%d 旧件=%d 购买记录=%d 条%n",
                        asLong(ledger.get("spent")),
                        asLong(ledger.get("admin")),
                        asLong(ledger.get("legacy")),
                        asList(ledger.get("buys")).size());
                // 1.0.7 新增：副本掉落与待入账。drop 是总额，drops 是逐日明细，两者必须相等
                long drop = asLong(ledger.get("drop"));
                long god = asLong(ledger.get("god"));
                long pend = sumValues(ledger.get("pend"));
                long dropSum = sumValues(ledger.get("drops"));
                if (drop != 0 || dropSum != 0 || pend != 0 || god != 0) {
                    System.out.printf("  副本掉落 : %d（明细合计 %d%s）%n",
                            drop, dropSum, drop == dropSum ? "" : "  ⚠ 不一致");
                    if (pend != 0) {
                        System.out.printf("  待入账   : %d%n", pend);
                    }
                    if (god != 0) {
                        System.out.printf("  god 入账 : %d%n", god);
                    }
                }
            }
        }

        Map<Object, Object> signin = asMap(payload.get("daily_signin"));
        if (!signin.isEmpty()) {
            System.out.println();
            System.out.println("== 签到 ==");
            System.out.printf("  累计天数 : %d%n", asLong(signin.get("total")));
            System.out.printf("  轮次     : %d%n", asLong(signin.get("cycle_day")));
            System.out.printf("  上次签到 : %s%n", asString(signin.get("last_date")));
        }

        List<Object> characters = asList(payload.get("characters"));
        System.out.println();
        System.out.printf("== 角色（%d 个槽位）==%n", characters.size());
        for (int slot = 0; slot < characters.size(); slot++) {
            Map<Object, Object> character = asMap(characters.get(slot));
            if (character.isEmpty()) {
                continue;
            }
            Map<Object, Object> identity = asMap(character.get("identity"));
            Map<Object, Object> progression = asMap(character.get("progression"));
            Map<Object, Object> inventory = asMap(character.get("inventory"));
            System.out.printf("  [%d] %-8s 等级=%-3d 功夫豆=%d%n",
                    slot,
                    asString(identity.get("name")),
                    asLong(progression.get("level")),
                    asLong(progression.get("kungfu_bean")));
            System.out.printf("      背包：道具 %d / 材料 %d / 装备 %d%n",
                    asList(inventory.get("items")).size(),
                    asList(inventory.get("materials")).size(),
                    asList(inventory.get("equipment")).size());
        }

        // 仓库（账号级，不属于任何角色；Python 版 show 也会列出来）
        List<Object> warehouse = asList(asMap(payload.get("account_storehouse")).get("entries"));
        if (!warehouse.isEmpty()) {
            System.out.println();
            System.out.printf("== 仓库（%d 条）==%n", warehouse.size());
            // 仓库动辄几十上百条，概览只列前 10 条，免得刷屏
            int shown = Math.min(warehouse.size(), WAREHOUSE_PREVIEW);
            for (int i = 0; i < shown; i++) {
                Map<Object, Object> stored = asMap(warehouse.get(i));
                System.out.printf("  id=%-10s type=%s%n",
                        asString(stored.get("id")), asString(stored.get("item_type")));
            }
            if (warehouse.size() > shown) {
                System.out.printf("  … 另有 %d 条（用 get account_storehouse.entries 可看全部）%n",
                        warehouse.size() - shown);
            }
        }
        return EXIT_OK;
    }

    /**
     * {@code verify}：只校验磁盘上的签名，不修改任何字节。
     *
     * @param save 存档路径
     * @return 退出码（验签失败返回 2）
     * @throws IOException         读盘失败
     * @throws SaveFormatException 解密失败
     */
    private static int verify(Path save) throws IOException, SaveFormatException {
        byte[] key = loadTrying(save).key();
        boolean ok = SaveDocument.verify(save, key);
        System.out.println("存档 : " + save.toAbsolutePath());
        System.out.println("签名 : " + (ok ? "通过（内容与签名一致）" : "失败（内容被改动或签名不匹配）"));
        return ok ? EXIT_OK : EXIT_ERROR;
    }

    /**
     * {@code list}：列出存档根目录下的所有账号存档。
     *
     * @return 退出码
     * @throws SaveFormatException 取不到存档目录
     */
    private static int list() throws SaveFormatException {
        List<Path> all = SaveLocator.listAll();
        System.out.println("存档目录：" + SaveLocator.savesRoot());
        if (all.isEmpty()) {
            System.out.println("  （没有任何存档）");
            return EXIT_OK;
        }
        for (Path path : all) {
            long size;
            try {
                size = Files.size(path);
            } catch (IOException e) {
                size = -1;
            }
            System.out.printf("  %s  %d 字节  %s%n",
                    SaveLocator.profileIdOf(path), size, path.toAbsolutePath());
        }
        return EXIT_OK;
    }

    /**
     * {@code get}：按点分路径读取一个值（只读，不写盘）。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读盘失败
     * @throws SaveFormatException 解密失败
     */
    private static int get(String[] args) throws IOException, SaveFormatException {
        if (args.length < 2) {
            System.err.println("用法：get <点分路径> [存档路径]");
            return EXIT_USAGE;
        }
        String dotted = args[1];
        Path save = resolveSave(args, 2);

        SaveDocument document = loadTrying(save).document();

        Map<Object, Object> payload = document.payload();
        Object cursor = payload;
        for (String step : dotted.split("\\.")) {
            if (cursor instanceof Map<?, ?> map) {
                cursor = map.get(step);
            } else if (cursor instanceof List<?> list) {
                int index = Integer.parseInt(step);
                cursor = (index >= 0 && index < list.size()) ? list.get(index) : null;
            } else {
                System.err.println("路径在「" + step + "」处无法继续：当前值不是容器");
                return EXIT_ERROR;
            }
            if (cursor == null) {
                System.err.println("路径不存在：" + dotted);
                return EXIT_ERROR;
            }
        }
        System.out.println(format(cursor));
        return EXIT_OK;
    }

    /**
     * {@code set}：按点分路径原地改一个整数（高级用法，会重算签名并备份）。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 路径或结构异常
     */
    private static int set(String[] args) throws IOException, SaveFormatException {
        if (args.length < 3) {
            System.err.println("用法：set <点分路径> <整数值> [存档路径]");
            return EXIT_USAGE;
        }
        String dotted = args[1];
        long value;
        try {
            value = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("第二个参数必须是整数：" + args[2]);
            return EXIT_USAGE;
        }
        Path save = resolveSave(args, 3);
        Opened opened = loadTrying(save);
        byte[] key = opened.key();

        SaveDocument document = opened.document();
        List<Object> path = parsePath(dotted);
        Long before = readLongAt(document.payload(), path);

        document.patchInt(path, value);
        long generation = document.refreshSignature();
        Path backup = document.saveWithBackup(save);
        boolean ok = SaveDocument.verify(save, key);

        System.out.println("字段     : " + dotted);
        System.out.println("原值     : " + (before == null ? "（未知）" : before));
        System.out.println("新值     : " + value);
        System.out.println("代数     : " + generation);
        System.out.println("原档备份 : " + backup);
        System.out.println("签名自检 : " + (ok ? "通过" : "失败"));
        return ok ? EXIT_OK : EXIT_ERROR;
    }

    /**
     * {@code bean}：改某个角色的功夫豆。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 结构异常
     */
    private static int bean(String[] args) throws IOException, SaveFormatException {
        if (args.length < 3) {
            System.err.println("用法：bean <卡槽> <数量> [存档]");
            return EXIT_USAGE;
        }
        int slot = Integer.parseInt(args[1]);
        long value = Long.parseLong(args[2]);
        Path save = resolveSave(args, 3);
        SaveService service = SaveService.open(save, ItemCatalog.loadDefault());
        service.setBean(slot, value);
        return report("功夫豆（卡槽 " + slot + "）", value, service.save());
    }

    /**
     * {@code demon}：改账户魔点。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 结构异常
     */
    private static int demon(String[] args) throws IOException, SaveFormatException {
        if (args.length < 2) {
            System.err.println("用法：demon <数量> [存档]");
            return EXIT_USAGE;
        }
        long value = Long.parseLong(args[1]);
        Path save = resolveSave(args, 2);
        SaveService service = SaveService.open(save, ItemCatalog.loadDefault());
        service.setDemonPoints(value);
        return report("魔点", value, service.save());
    }

    /**
     * {@code tongbao}：把今天的副本掉落额度补满。
     *
     * <p>通宝带账本校验，凭空加钱会写出游戏一读就报 {@code tongbao_ledger} 的存档，
     * 所以这里只保留游戏自身的记账渠道：今天的掉落补到每日上限，多一分都不写。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 今天没有掉落记录或已经领满
     */
    private static int tongbao(String[] args) throws IOException, SaveFormatException {
        Path save = resolveSave(args, 1);
        SaveService service = SaveService.open(save, ItemCatalog.loadDefault());
        long added = service.claimTodayDrop();
        SaveOutcome outcome = service.save();
        System.out.println("今日副本额度 +" + added + "，通宝 → " + service.currencies().tongbao());
        return report("今日额度", added, outcome);
    }

    /**
     * {@code repair-signin}：把签到天数压回今天可达的上限，修复「一点签到就报 tongbao_ledger」。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 结构异常
     */
    private static int repairSignin(String[] args) throws IOException, SaveFormatException {
        Path save = resolveSave(args, 1);
        SaveService service = SaveService.open(save, ItemCatalog.loadDefault());
        Currencies before = service.currencies();
        TongbaoPlan plan = service.compactSignin();
        SaveOutcome outcome = service.save();
        System.out.println("签到     : " + before.signinTotal() + " 天 / " + before.lastSigninDate()
                + " → " + plan.total() + " 天 / " + plan.lastDate() + "（今天可再签一次）");
        System.out.println("通宝     : " + before.tongbao() + " → " + plan.balance());
        System.out.println("账本     : 花费 " + plan.spent() + " · 购买记录 " + before.buySum()
                + " · 掉落 " + before.drop());
        return report("整理账本", plan.total(), outcome);
    }

    /**
     * {@code signin}：把累计签到天数顶到指定值（超过今天可达上限会被压回上限）。
     *
     * <p>账本自洽，存档能正常载入，背包、商城、装备、副本等功能都不受影响；
     * 天数压到今天可达上限以内时，游戏内签到同样正常。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 结构异常
     */
    private static int signin(String[] args) throws IOException, SaveFormatException {
        if (args.length < 2) {
            System.err.println("用法：signin <天数> [余额] [存档]");
            return EXIT_USAGE;
        }
        int days = Integer.parseInt(args[1]);
        long target = Long.MAX_VALUE;
        int nextIndex = 2;
        if (args.length > 2 && args[2].matches("\\d+")) {
            target = Long.parseLong(args[2]);
            nextIndex = 3;
        }
        Path save = resolveSave(args, nextIndex);
        SaveService service = SaveService.open(save, ItemCatalog.loadDefault());
        TongbaoPlan plan = service.applySigninDays(days, target);
        SaveOutcome outcome = service.save();
        System.out.println("签到方案 : " + plan.describe().replace("\n", " | "));
        if (plan.forced()) {
            System.out.println("注意     : 想要的天数超过今天可达上限，已压到 "
                    + plan.total() + " 天（写未来日期会让游戏内签到报 tongbao_ledger）。");
        }
        return report("签到天数", plan.total(), outcome);
    }

    /**
     * {@code item}：按「卡槽 + 分区 + 下标」精确改一件物品的数量。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 结构异常
     */
    private static int item(String[] args) throws IOException, SaveFormatException {
        if (args.length < 5) {
            System.err.println("用法：item <卡槽> <分区> <下标> <数量> [存档]");
            return EXIT_USAGE;
        }
        int slot = Integer.parseInt(args[1]);
        String section = args[2];
        int index = Integer.parseInt(args[3]);
        long count = Long.parseLong(args[4]);
        Path save = resolveSave(args, 5);
        SaveService service = SaveService.open(save, ItemCatalog.loadDefault());
        service.setItemCount(slot, section, index, count);
        return report("物品 %s[%d]".formatted(section, index), count, service.save());
    }

    /**
     * {@code itemid}：按物品 ID 改数量，跨所有角色生效。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 结构异常
     */
    private static int itemById(String[] args) throws IOException, SaveFormatException {
        if (args.length < 3) {
            System.err.println("用法：itemid <物品ID> <数量> [存档]");
            return EXIT_USAGE;
        }
        int itemId = Integer.parseInt(args[1]);
        long count = Long.parseLong(args[2]);
        Path save = resolveSave(args, 3);
        SaveService service = SaveService.open(save, ItemCatalog.loadDefault());
        int changed = service.setItemCountById(itemId, count);
        if (changed == 0) {
            System.err.println("没有找到物品 #" + itemId + "（没有任何角色持有它）");
            return EXIT_ERROR;
        }
        System.out.println("命中     : %d 处（跨全部角色）".formatted(changed));
        return report("物品 #" + itemId, count, service.save());
    }

    /**
     * {@code all}：把所有角色的道具/材料数量统一改成同一个值。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 结构异常
     */
    private static int all(String[] args) throws IOException, SaveFormatException {
        if (args.length < 2) {
            System.err.println("用法：all <数量> [存档]");
            return EXIT_USAGE;
        }
        long count = Long.parseLong(args[1]);
        Path save = resolveSave(args, 2);
        SaveService service = SaveService.open(save, ItemCatalog.loadDefault());
        int changed = service.setAllItemCounts(count);
        if (changed == 0) {
            System.err.println("这份存档里没有可修改的物品");
            return EXIT_ERROR;
        }
        System.out.println("命中     : %d 件（跨全部角色）".formatted(changed));
        return report("全部物品数量", count, service.save());
    }

    /**
     * {@code add}：向指定角色的背包添加物品（已有同 ID 则累加）。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws IOException         读写失败
     * @throws SaveFormatException 结构异常
     */
    private static int add(String[] args) throws IOException, SaveFormatException {
        if (args.length < 4) {
            System.err.println("用法：add <卡槽> <物品ID> <数量> [存档]");
            return EXIT_USAGE;
        }
        int slot = Integer.parseInt(args[1]);
        int itemId = Integer.parseInt(args[2]);
        long count = Long.parseLong(args[3]);
        Path save = resolveSave(args, 4);
        SaveService service = SaveService.open(save, ItemCatalog.loadDefault());
        SaveService.AddResult result = service.addItem(slot, itemId, count, null);
        System.out.printf("添加 %s（#%d）→ 卡槽 %d 的 %s，%s，现有 %d 件%n",
                result.name(), result.itemId(), slot, result.section(),
                result.merged() ? "已累计到 " + result.count() : "新增 " + result.count(),
                result.sectionSize());
        return report("添加物品", result.count(), service.save());
    }

    /**
     * 打印写操作结果的统一格式。
     *
     * @param what    改了什么
     * @param value   改成了多少
     * @param outcome 保存结果
     * @return 退出码（签名自检失败返回 2）
     */
    private static int report(String what, long value, SaveOutcome outcome) {
        System.out.println("修改     : " + what + " → " + value);
        System.out.println("代数     : " + outcome.generation());
        System.out.println("原档备份 : " + outcome.backup());
        System.out.println("签名自检 : " + (outcome.verified() ? "通过" : "失败"));
        return outcome.verified() ? EXIT_OK : EXIT_ERROR;
    }

    /**
     * 打印帮助。
     *
     * @return 退出码（永远是 0）
     */
    private static int usage() {
        System.out.println("""
                xiewa-save —— 功夫派存档命令行工具

                用法：
                  xiewa-save info [存档]                 查看存档概览（默认）
                  xiewa-save verify [存档]               校验签名是否自洽
                  xiewa-save list                        列出全部账号存档
                  xiewa-save keys [存档]                 显示密钥来源
                  xiewa-save harvest                     收录本机所有账号密钥（换电脑后可用）
                  xiewa-save get <点分路径> [存档]        读取某个值
                  xiewa-save set <点分路径> <整数> [存档]  原始改值
                  xiewa-save bean <卡槽> <数量>           改功夫豆
                  xiewa-save demon <数量>                改魔点
                  xiewa-save tongbao [存档]               补满今天的副本掉落额度（通宝唯一入口）
                  xiewa-save signin <天数> [余额]         设定签到天数（超过今天上限会被压回）
                  xiewa-save repair-signin [存档]         把签到压回今天可达上限（修复签到被拒）
                  xiewa-save item <卡槽> <分区> <下标> <数量>
                  xiewa-save itemid <物品ID> <数量>       按 ID 改数量（跨全部角色）
                  xiewa-save all <数量>                  全部物品改成同一数量
                  xiewa-save add <卡槽> <物品ID> <数量>    添加物品

                省略存档路径时，自动取最近修改的那份。
                """);
        return EXIT_OK;
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * {@code keys}：显示本机密钥来源与解出来的密钥（排障用）。
     *
     * @param save 存档路径
     * @return 退出码
     * @throws SaveFormatException 两条密钥来源都不可用
     */
    private static int keys(Path save) throws IOException, SaveFormatException {
        System.out.println("本机状态 : " + SaveKeys.localStatusText());
        System.out.println("密钥库   : " + SaveKeys.vaultStatusText());
        System.out.println("账号     : " + SaveKeys.profileIdOf(save));

        List<SaveKeys.Resolution> candidates = SaveKeys.candidates(save);
        if (candidates.isEmpty()) {
            System.out.println("密钥     : 没有任何来源（把修改器放到游戏目录再试）");
            return EXIT_ERROR;
        }
        System.out.println("候选密钥 : " + candidates.size() + " 个");
        SaveKeys.Resolution working = null;
        for (int i = 0; i < candidates.size(); i++) {
            SaveKeys.Resolution candidate = candidates.get(i);
            String state;
            try {
                SaveDocument.load(save, candidate.key());
                state = "✓ 能解开";
                if (working == null) {
                    working = candidate;
                }
            } catch (SaveFormatException e) {
                state = "✗ " + e.getMessage();
            }
            System.out.printf("  [%d] %s — %s%n      结果：%s%n",
                    i + 1, candidate.source().label(), candidate.detail(), state);
        }
        if (working == null) {
            return EXIT_ERROR;
        }
        System.out.println();
        System.out.println("实际使用 : " + java.util.HexFormat.of().formatHex(working.key()));
        System.out.println("来源     : " + working.source().label() + " — " + working.detail());
        return EXIT_OK;
    }

    /**
     * {@code harvest}：把本机所有游戏账号的密钥收进密钥库。
     *
     * <p>在装有游戏的电脑上跑一次，之后整个「chengyi-updatepai」文件夹拷到任何电脑，
     * 都能直接打开这些账号的存档——不需要用户额外保存任何密钥文件。
     *
     * @return 退出码
     */
    private static int harvest() {
        System.out.println("本机状态 : " + SaveKeys.localStatusText());
        int stored = SaveKeys.harvest();
        System.out.println(stored == 0
                ? "没有新密钥可以收录（可能此前已收录过，或本机没有游戏/账号）"
                : "已收录 " + stored + " 个账号的密钥");
        System.out.println("密钥库   : " + SaveKeys.vaultStatusText());
        return EXIT_OK;
    }

    /**
     * 解析末尾的存档路径参数；缺省则取默认存档。
     */
    private static Path resolveSave(String[] args, int from) throws IOException, SaveFormatException {
        if (args.length > from) {
            Path candidate = Paths.get(args[from]).toAbsolutePath();
            if (!Files.isRegularFile(candidate)) {
                throw new SaveFormatException("指定的存档不存在：" + candidate);
            }
            return candidate;
        }
        return SaveLocator.defaultSave();
    }

    /**
     * 把 {@code a.b.0.count} 这样的点分路径拆成路径元素，纯数字段视为数组下标。
     */
    private static List<Object> parsePath(String dotted) {
        List<Object> path = new ArrayList<>();
        for (String segment : dotted.split("\\.")) {
            if (segment.matches("-?\\d+")) {
                path.add(Integer.parseInt(segment));
            } else {
                path.add(segment);
            }
        }
        return path;
    }

    /**
     * 按路径读取一个整数，用于展示「改前值」。
     *
     * @return 读不到或不是数字时返回 {@code null}
     */
    private static Long readLongAt(Map<Object, Object> root, List<Object> path) {
        Object cursor = root;
        for (Object step : path) {
            if (cursor instanceof Map<?, ?> map) {
                cursor = map.get(step);
            } else if (cursor instanceof List<?> list && step instanceof Integer index) {
                cursor = (index >= 0 && index < list.size()) ? list.get(index) : null;
            } else {
                return null;
            }
            if (cursor == null) {
                return null;
            }
        }
        return cursor instanceof Number number ? number.longValue() : null;
    }

    /**
     * 宽松取值：Map 强转（兼容 {@code Map<?, ?>} 的写法，省去每处都加注解）。
     *
     * @param value 任意值
     * @return 视图映射；不是 Map 时返回空 Map
     */
    private static Map<Object, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> cast = (Map<Object, Object>) map;
            return cast;
        }
        return Map.of();
    }

    /**
     * 宽松取列表：不是 List 时返回空列表。
     *
     * @param value 任意值
     * @return 列表视图
     */
    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> cast = (List<Object>) list;
            return cast;
        }
        return List.of();
    }

    /**
     * 取数值（非数字按 0 处理）。
     *
     * @param value 任意值
     * @return 长整型值
     */
    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 把「日期 → 金额」形式的表（如 {@code drops} / {@code pend}）求和。
     *
     * @param value 任意值
     * @return 各项金额之和；不是表时返回 0
     */
    private static long sumValues(Object value) {
        long sum = 0;
        for (Object item : asMap(value).values()) {
            sum += asLong(item);
        }
        return sum;
    }

    /**
     * 取字符串（null 变成空串，避免打印出 "null"）。
     *
     * @param value 任意值
     * @return 字符串
     */
    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 把 Variant 值压成一行可读文本（容器只报条目数，避免刷屏）。
     *
     * @param value 任意值
     * @return 文本
     */
    private static String format(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append(": ").append(format(entry.getValue()));
                if (count >= 12) {
                    sb.append(", …（共 ").append(map.size()).append(" 项）");
                    break;
                }
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            return "[共 " + list.size() + " 项]";
        }
        if (value instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]";
        }
        return String.valueOf(value);
    }
}
