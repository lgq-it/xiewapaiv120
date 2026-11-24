package com.chengyi.xiewa.save.model;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.chengyi.xiewa.save.core.FaeCodec;
import com.chengyi.xiewa.save.core.GuardMac;
import com.chengyi.xiewa.save.core.SaveCrypto;
import com.chengyi.xiewa.save.core.SaveFormatException;
import com.chengyi.xiewa.save.format.VariantNavigator;
import com.chengyi.xiewa.save.format.VariantReader;
import com.chengyi.xiewa.save.format.VariantSpan;

/**
 * 一份已解密的存档文档。
 *
 * <p>存档明文是一个 {@code store_var} 结构：外层是 4 字节长度 + 一个字典，
 * 字典里固定三样东西：
 * <ul>
 *   <li>{@code header} —— 版本、代数（generation）等元信息</li>
 *   <li>{@code payload} —— 真正的游戏数据（角色、货币、背包…）</li>
 *   <li>{@code mac} —— 对 header + payload 原始字节做的 HMAC-SHA256</li>
 * </ul>
 *
 * <p>修改流程必须严格按这个顺序：<b>原地改数值 → 代数 +1 → 用改动后的
 * header+payload 字节重算签名 → 写回</b>。任何「重新序列化整个结构」的做法都会
 * 让字节布局变化，签名必然对不上。
 */
public final class SaveDocument {

    /** 外层字典在明文中的固定偏移（前面是 4 字节长度）。 */
    private static final int ENVELOPE_OFFSET = 4;

    private static final String KEY_HEADER = "header";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_MAC = "mac";
    private static final String FIELD_GENERATION = "generation";
    /** 签名方案版本字段，1.0.9 起出现在 header 里（值为 2）。 */
    private static final String FIELD_MAC_VERSION = "mac_v";

    /** 备份保留时长。 */
    public static final long BACKUP_RETENTION_HOURS = 24;

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 备份文件名中的标识，用于识别与清理。 */
    private static final String BACKUP_MARKER = ".editor-backup-";

    private final byte[] saveKey;
    private final byte[] macKey;
    /** 签名方案版本：1 = 1.0.8 及以前；2 = 1.0.9 起的 guard sign2。 */
    private final int macVersion;
    private byte[] plain;
    private VariantNavigator navigator;
    private VariantReader reader;
    private Map<String, Segment> envelope;

    private SaveDocument(byte[] plain, byte[] saveKey) throws SaveFormatException {
        this.plain = plain;
        this.saveKey = saveKey.clone();
        this.navigator = new VariantNavigator(plain);
        this.reader = new VariantReader(navigator);
        this.envelope = readEnvelope();
        this.macVersion = detectMacVersion(navigator, envelope.get(KEY_HEADER).start());
        this.macKey = macKeyFor(saveKey, macVersion);
    }

    /**
     * 按签名方案版本派生 MAC 密钥。
     *
     * @param saveKey    32 字节存档密钥
     * @param macVersion 1 = HKDF 旧方案；≥2 = guard 的 {@code sign2} 方案
     * @return MAC 密钥
     */
    private static byte[] macKeyFor(byte[] saveKey, int macVersion) {
        return macVersion >= 2
                ? GuardMac.prk(GuardMac.SAVE_DOMAIN, saveKey)
                : SaveCrypto.deriveMacKey(saveKey);
    }

    /**
     * 从 header 里读 {@code mac_v}，决定用哪套签名方案。
     *
     * <p>1.0.8 的存档没有这个字段（或为 1），1.0.9 起写 2。
     *
     * @param nav          导航器
     * @param headerOffset header 值在明文中的偏移
     * @return 版本号，读不到时按 1 处理
     */
    private static int detectMacVersion(VariantNavigator nav, int headerOffset) {
        try {
            int target = nav.locate(headerOffset, List.of(FIELD_MAC_VERSION));
            VariantSpan span = nav.spanAt(target);
            long value = span.isWideNumeric()
                    ? nav.readI64(span.valueOffset())
                    : nav.readI32(span.valueOffset());
            return (int) value;
        } catch (SaveFormatException | RuntimeException e) {
            return 1;
        }
    }

    /** 信封中某一段的字节范围。 */
    private record Segment(int start, int end) {
    }

    /**
     * 从磁盘加载并解密存档。
     *
     * @param path     save.dat 路径
     * @param saveKey  32 字节存档密钥
     * @return 已解密的文档
     * @throws IOException         读取失败
     * @throws SaveFormatException 格式不符或密钥错误
     */
    public static SaveDocument load(Path path, byte[] saveKey) throws IOException, SaveFormatException {
        Objects.requireNonNull(path, "path");
        byte[] raw = Files.readAllBytes(path);
        byte[] plain = FaeCodec.decode(raw, saveKey);
        return new SaveDocument(plain, saveKey);
    }

    /**
     * 解析后的游戏数据（{@code payload} 段）。
     *
     * @return 字典的浅层副本引用（内部为 {@link LinkedHashMap}）
     */
    public Map<Object, Object> payload() {
        Object value = reader.read(envelope.get(KEY_PAYLOAD).start());
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("payload 不是字典，存档结构异常");
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> map = (Map<Object, Object>) value;
        return map;
    }

    /**
     * 字节流导航器（供需要精确偏移的业务逻辑使用）。
     *
     * @return 导航器
     */
    public VariantNavigator navigator() {
        return navigator;
    }

    /**
     * {@code payload} 段的起始偏移（下钻路径的起点）。
     *
     * @return 偏移
     */
    public int payloadOffset() {
        return envelope.get(KEY_PAYLOAD).start();
    }

    /**
     * {@code header} 段的起始偏移（下钻路径的起点）。
     *
     * <p>用于直接改写 header 字段（如 {@code generation} / {@code prev_hash}），
     * 绕开「按内容重算签名时自动让代数 +1」的固定流程——修复与水位记录的链式关系时
     * 必须能精确指定代数，不能盲目自增。
     *
     * @return 偏移
     */
    public int headerOffset() {
        return envelope.get(KEY_HEADER).start();
    }

    /**
     * 在指定偏移处插入一段字节，并把后续结构整体后移。
     *
     * <p>这是「添加物品」的核心手段：Godot 的数组没有预留空位，只能把新元素编码好之后
     * 插到数组末尾，再修正元素个数与外层长度。
     *
     * <p><b>调用顺序很重要</b>：本方法只搬运字节并重建导航器，<b>不会</b>重新解析信封结构——
     * 因为此时容器里的元素个数还是旧值，解析必然错位。调用方必须在改完元素个数后
     * 显式调用 {@link #rebuildEnvelope()}，之后才能继续访问 {@code payload} / 签名。
     *
     * @param offset 插入点
     * @param insert 要插入的字节
     */
    public void splice(int offset, byte[] insert) {
        if (offset < 0 || offset > plain.length) {
            throw new IllegalArgumentException(
                    "插入点越界：%d（明文长度 %d）".formatted(offset, plain.length));
        }
        byte[] grown = new byte[plain.length + insert.length];
        System.arraycopy(plain, 0, grown, 0, offset);
        System.arraycopy(insert, 0, grown, offset, insert.length);
        System.arraycopy(plain, offset, grown, offset + insert.length, plain.length - offset);
        this.plain = grown;
        this.navigator = new VariantNavigator(grown);
        this.reader = new VariantReader(navigator);
    }

    /**
     * 删除明文中一段字节，并把后续结构整体前移。
     *
     * @param from 删除起点（含）
     * @param to   删除终点（不含）
     */
    public void deleteRange(int from, int to) {
        if (from < 0 || to > plain.length || from > to) {
            throw new IllegalArgumentException(
                    "删除区间越界：%d..%d（明文长度 %d）".formatted(from, to, plain.length));
        }
        int remove = to - from;
        byte[] shrunk = new byte[plain.length - remove];
        System.arraycopy(plain, 0, shrunk, 0, from);
        System.arraycopy(plain, to, shrunk, from, plain.length - to);
        this.plain = shrunk;
        this.navigator = new VariantNavigator(shrunk);
        this.reader = new VariantReader(navigator);
    }

    /**
     * 移除 header 里的 {@code ledger_snap}，让游戏走“旧档迁移”，
     * 下次保存时由游戏重新建立账本快照。
     *
     * @throws SaveFormatException header 结构异常
     */
    public void stripLedgerSnap() throws SaveFormatException {
        int headerOffset = envelope.get(KEY_HEADER).start();
        List<VariantNavigator.DictEntry> entries = navigator.dictEntries(headerOffset);
        int snapKey = -1;
        int snapNext = -1;
        for (int i = 0; i < entries.size(); i++) {
            if ("ledger_snap".equals(String.valueOf(entries.get(i).key()))) {
                snapKey = entries.get(i).keyOffset();
                snapNext = i + 1 < entries.size() ? entries.get(i + 1).keyOffset() : -1;
                break;
            }
        }
        if (snapKey < 0) {
            return; // 本来就没有快照，无需处理
        }
        if (snapNext < 0) {
            throw new SaveFormatException("ledger_snap 不是最后一个 header 字段，无法安全移除");
        }
        int countOffset = headerOffset + VariantSpan.HEADER_SIZE;
        int count = navigator.readU32(countOffset);
        deleteRange(snapKey, snapNext);
        navigator.writeU32(countOffset, count - 1);
        rebuildEnvelope();
        refreshPlainLength();
        refreshSignature(false);
    }

    /**
     * 按当前字节内容重新解析信封（header / payload / mac 的偏移）。
     *
     * <p>结构被插入改动后必须调用一次，否则后续读写都会用过期偏移。
     *
     * @throws SaveFormatException 结构不合法
     */
    public void rebuildEnvelope() throws SaveFormatException {
        this.envelope = readEnvelope();
    }

    /**
     * 修正明文首部的总长度字段（{@code store_var} 的长度前缀）。
     *
     * <p>插入或删除字节后必须同步，否则游戏读取时会截断或越界。
     */
    public void refreshPlainLength() {
        navigator.writeU32(0, plain.length - 4);
    }

    /**
     * 明文总长度（解密后、加容器头之前）。
     *
     * @return 字节数
     */
    public int plainLength() {
        return plain.length;
    }

    /**
     * 读取明文中的原始字节，用于诊断与测试。
     *
     * @param offset 起始偏移
     * @param length 长度
     * @return 副本
     */
    public byte[] rawBytes(int offset, int length) {
        return navigator.copyBytes(offset, length);
    }

    /**
     * 读取当前内容的代数（generation）。
     *
     * @return 代数
     * @throws SaveFormatException 字段异常
     */
    /** 解密后的完整明文字节（header+payload 序列化原文），供诊断工具计算内容指纹。 */
    public byte[] plainBytes() {
        return plain.clone();
    }

    public long generation() throws SaveFormatException {
        int target = navigator.locate(envelope.get(KEY_HEADER).start(), List.of(FIELD_GENERATION));
        VariantSpan span = navigator.spanAt(target);
        return span.isWideNumeric()
                ? navigator.readI64(span.valueOffset())
                : navigator.readI32(span.valueOffset());
    }

    /**
     * 原地改写 payload 里的一个整数。
     *
     * @param path  从 payload 顶部算起的路径，例如 {@code ["account_currencies", "tongbao"]}
     * @param value 新值
     * @throws SaveFormatException 路径无效或目标非整数
     */
    public void patchInt(List<?> path, long value) throws SaveFormatException {
        navigator.patchInt(envelope.get(KEY_PAYLOAD).start(), path, value);
    }

    /**
     * 原地改写 payload 里的一个布尔值（占用定长，可安全翻转）。
     *
     * @param path  路径，例如 {@code ["god_mode"]}
     * @param value 新值
     * @throws SaveFormatException 路径无效或目标非布尔
     */
    public void patchBool(List<?> path, boolean value) throws SaveFormatException {
        navigator.patchBool(envelope.get(KEY_PAYLOAD).start(), path, value);
    }

    /**
     * 原地改写 payload 里的一个字符串（要求字节占用不变）。
     *
     * @param path  路径
     * @param value 新字符串
     * @throws SaveFormatException 路径无效、目标非字符串，或字节占用变化
     */
    public void patchString(List<?> path, String value) throws SaveFormatException {
        navigator.patchString(envelope.get(KEY_PAYLOAD).start(), path, value);
    }

    /**
     * 按当前内容重算签名，并让代数自增（游戏据此判定存档比内存副本新）。
     *
     * @return 新的代数
     * @throws SaveFormatException 字段异常
     */
    public long refreshSignature() throws SaveFormatException {
        return refreshSignature(true);
    }

    /**
     * 按当前内容重算签名；代数是否自增由调用方决定。
     *
     * <p>1.0.9（build 10017）起游戏收紧了「存档水位」校验：写入时把代数 +1，
     * 游戏会判定这份存档对不上水位记录、直接回滚恢复（内容全丢）。对策是
     * <b>保持代数不变</b>，让游戏走「内容与水位记录不符」的回滚窗口通道
     * （{@code R3f}）接受这份存档。
     *
     * @param bumpGeneration true = 代数 +1（1.0.8 及以前的策略）；false = 保持原代数
     * @return 写入后的代数
     * @throws SaveFormatException 字段异常
     */
    public long refreshSignature(boolean bumpGeneration) throws SaveFormatException {
        long nextGeneration = bumpGeneration ? generation() + 1 : generation();
        if (bumpGeneration) {
            navigator.patchInt(envelope.get(KEY_HEADER).start(), List.of(FIELD_GENERATION), nextGeneration);
        }

        byte[] body = concatenate(
                Arrays.copyOfRange(plain, envelope.get(KEY_HEADER).start(), envelope.get(KEY_HEADER).end()),
                Arrays.copyOfRange(plain, envelope.get(KEY_PAYLOAD).start(), envelope.get(KEY_PAYLOAD).end()));
        byte[] mac = SaveCrypto.hmacSha256(macKey, body);

        Segment macSegment = envelope.get(KEY_MAC);
        int macDataOffset = navigator.spanAt(macSegment.start()).valueOffset();
        int available = macSegment.end() - macDataOffset;
        if (available < mac.length) {
            throw new SaveFormatException(
                    "签名段长度不足：需要 %d 字节，字段只有 %d 字节".formatted(mac.length, available));
        }
        navigator.writeBytes(macDataOffset, mac, mac.length);
        return nextGeneration;
    }

    /**
     * 校验磁盘上某份存档的签名是否与内容自洽。
     *
     * @param path    存档路径
     * @param saveKey 存档密钥
     * @return 自洽返回 true
     * @throws IOException         读取失败
     * @throws SaveFormatException 无法解密
     */
    public static boolean verify(Path path, byte[] saveKey) throws IOException, SaveFormatException {
        byte[] plain = FaeCodec.decode(Files.readAllBytes(path), saveKey);
        VariantNavigator nav = new VariantNavigator(plain);
        Map<String, Segment> segments = new LinkedHashMap<>();
        for (VariantNavigator.DictEntry entry : nav.dictEntries(ENVELOPE_OFFSET)) {
            segments.put(entry.key().toString(),
                    new Segment(entry.valueOffset(), entry.valueEndOffset()));
        }
        Segment header = segments.get(KEY_HEADER);
        Segment payload = segments.get(KEY_PAYLOAD);
        if (header == null || payload == null || !segments.containsKey(KEY_MAC)) {
            throw new SaveFormatException("存档缺少 header / payload / mac 字段");
        }
        byte[] body = concatenate(
                Arrays.copyOfRange(plain, header.start(), header.end()),
                Arrays.copyOfRange(plain, payload.start(), payload.end()));

        int macVariant = segments.get(KEY_MAC).start();
        VariantSpan macSpan = nav.spanAt(macVariant);
        byte[] stored = nav.copyBytes(macSpan.valueOffset(), macSpan.valueLength());

        int version = detectMacVersion(nav, header.start());
        if (Arrays.equals(SaveCrypto.hmacSha256(macKeyFor(saveKey, version), body), stored)) {
            return true;
        }
        // 版本判定失误时的兜底：两套方案都试一遍，避免自检误报
        for (int fallback : new int[]{version, version >= 2 ? 1 : 2}) {
            if (Arrays.equals(SaveCrypto.hmacSha256(macKeyFor(saveKey, fallback), body), stored)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 备份原档并写回磁盘。
     *
     * <p>备份统一放在存档同目录的 {@code backup/} 下，并清理超过
     * {@link #BACKUP_RETENTION_HOURS} 小时的旧备份。写入先落临时文件再原子替换，
     * 避免写一半断电导致存档损坏。
     *
     * <p><b>防回退保险</b>：写盘前重读磁盘上的存档代数，若磁盘比当前内存副本新，
     * 说明期间游戏跑过并存了档——此时落盘会把新进度整个盖回旧档，
     * 游戏的水位守卫会判定「存档已被回退或替换」而拒绝读取，
     * 所以必须拒绝写入并提示先刷新。
     *
     * @param path 目标存档路径
     * @return 备份文件路径
     * @throws IOException          读写失败
     * @throws SaveFormatException  磁盘存档比内存副本新（防回退拦截），或格式异常
     */
    public Path saveWithBackup(Path path) throws IOException, SaveFormatException {
        Long diskGen = diskGeneration(path);
        if (diskGen != null && diskGen > generation()) {
            throw new SaveFormatException((
                    "存档在打开后又被游戏更新过（磁盘代数 %d > 当前副本 %d），"
                            + "继续写入会覆盖新进度并导致游戏报「存档已被回退或替换」。"
                            + "请重新打开存档后再做修改。").formatted(diskGen, generation()));
        }
        Path backup = backup(path);
        byte[] encrypted = FaeCodec.encode(plain, saveKey, null);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, encrypted);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        return backup;
    }

    /**
     * 读磁盘上现存档的代数；文件不存在或读不出时返回 null（交由后续流程处理）。
     *
     * @param path 存档路径
     * @return 代数，无法判定时为 null
     */
    private Long diskGeneration(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return new SaveDocument(FaeCodec.decode(Files.readAllBytes(path), saveKey), saveKey)
                    .generation();
        } catch (IOException | SaveFormatException | RuntimeException e) {
            return null;
        }
    }

    private Path backup(Path path) throws IOException {
        Path directory = path.toAbsolutePath().getParent();
        if (directory == null) {
            throw new IOException("无法确定存档所在目录：" + path);
        }
        // 与水位快照一致：备份不落在游戏会扫描的存档目录里。
        String profileId = directory.getFileName().toString();
        String localAppData = System.getenv("LOCALAPPDATA");
        Path backupDir = Path.of(localAppData == null ? directory.toString() : localAppData,
                "chengyi-updatepai", "backups", profileId);
        Files.createDirectories(backupDir);

        String name = path.getFileName() + BACKUP_MARKER
                + LocalDateTime.now().format(BACKUP_STAMP);
        Path target = backupDir.resolve(name);
        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        pruneOldBackups(backupDir);
        return target;
    }

    private static void pruneOldBackups(Path backupDir) throws IOException {
        LocalDateTime deadline = LocalDateTime.now().minus(BACKUP_RETENTION_HOURS, ChronoUnit.HOURS);
        List<Path> expired = new ArrayList<>();
        try (Stream<Path> stream = Files.list(backupDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().contains(BACKUP_MARKER))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant()
                                    .isBefore(deadline.atZone(java.time.ZoneId.systemDefault()).toInstant());
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(expired::add);
        }
        for (Path old : expired) {
            Files.deleteIfExists(old);
        }
    }

    private Map<String, Segment> readEnvelope() throws SaveFormatException {
        List<VariantNavigator.DictEntry> entries = navigator.dictEntries(ENVELOPE_OFFSET);
        Map<String, Segment> segments = new LinkedHashMap<>();
        for (VariantNavigator.DictEntry entry : entries) {
            segments.put(entry.key().toString(),
                    new Segment(entry.valueOffset(), entry.valueEndOffset()));
        }
        for (String required : List.of(KEY_HEADER, KEY_PAYLOAD, KEY_MAC)) {
            if (!segments.containsKey(required)) {
                throw new SaveFormatException(
                        "存档缺少字段「%s」，可能不是 XIEWA_SAVE v4 格式（现有字段：%s）"
                                .formatted(required, String.join(", ", segments.keySet())));
            }
        }
        return segments;
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
