package com.chengyi.xiewa.save.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import com.chengyi.xiewa.save.cli.SaveLocator;
import com.chengyi.xiewa.save.core.SaveFormatException;
import com.chengyi.xiewa.save.keys.SaveKeys;
import com.chengyi.xiewa.save.model.AntiCheatState;
import com.chengyi.xiewa.save.model.CharacterView;
import com.chengyi.xiewa.save.model.Currencies;
import com.chengyi.xiewa.save.model.HellTongbao;
import com.chengyi.xiewa.save.model.ItemCatalog;
import com.chengyi.xiewa.save.model.ItemView;
import com.chengyi.xiewa.save.model.SaveMeta;
import com.chengyi.xiewa.save.model.SaveOutcome;
import com.chengyi.xiewa.save.model.SaveService;
import com.chengyi.xiewa.save.model.WatermarkSnapshot;

/**
 * 主窗口。
 *
 * <p>布局思路：顶部选存档，左侧改货币与切角色，右侧改背包，底部看日志。
 *
 * <p>所有写操作都遵循同一个流程——<b>改一个字段 → 立刻落盘 → 回读刷新界面</b>，
 * 从不攒着批量写。这样任何一步失败都不会在内存里留下半截状态，
 * 用户看到的界面永远是磁盘上那份存档的真实样子。
 *
 * <p>另外两个贯穿全局的机制：
 * <ul>
 *   <li><b>防呆锁定</b>：每 2 秒探测一次 {@code XiewaPai.exe}，游戏一开就把所有编辑控件锁死，
 *       避免"改完被游戏覆盖"这种既白干又容易坏档的情况。</li>
 *   <li><b>失败必有反馈</b>：任何异常都会写进日志并弹窗，绝不允许"点了没反应"。</li>
 * </ul>
 */
public final class MainFrame extends JFrame implements CharacterCard.Listener {

    private static final long serialVersionUID = 1L;

    /** 窗口标题（与游戏存档命名保持一致，方便玩家认）。 */
    private static final String TITLE = "chengyi-updatepai - XiewaPai Save Editor";

    /** 修改器版本号：游戏版本_修改器版本。 */
    private static final String APP_VERSION = "V1.2.0_02";

    /** 存档下拉项里的时间格式。 */
    private static final DateTimeFormatter SAVE_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 游戏进程轮询间隔（毫秒）。 */
    private static final int WATCH_INTERVAL = 2000;

    /** 日志区最多保留的字符数，超出后从头裁掉，避免长时间运行吃内存。 */
    private static final int LOG_LIMIT = 60_000;

    /** 自定义存档（用户通过「浏览…」选进来的那份）在下拉框里的前缀。 */
    private static final String CUSTOM_PREFIX = "自定义";

    /** 一条存档条目（下拉框项）。 */
    private record SaveItem(Path path, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** 物品目录（内置，进程内共用一份）。 */
    private final ItemCatalog catalog = ItemCatalog.loadDefault();

    /** 卡槽号 → 角色卡片，用于批量改选中态与锁定。 */
    private final Map<Integer, CharacterCard> cards = new LinkedHashMap<>();

    /** 当前背包网格里的格子，用于切换选中态。 */
    private final List<ItemCell> cells = new ArrayList<>();

    private IconRepository icons;
    private SaveService service;
    private SaveKeys.Resolution keyInfo;

    /** 当前页签要显示的物品列表（道具 / 材料 二份之一，顺序与存档一致）。 */
    private List<ItemView> currentItems = List.of();

    /** 两个分区各存一份，切页签只换引用，不重新读档。 */
    private List<ItemView> slotItems = List.of();
    private List<ItemView> slotMaterials = List.of();
    private List<ItemView> slotEquipment = List.of();
    private int currentSlot = -1;
    private ItemView selectedItem;

    /** 游戏是否在运行（防呆锁定的依据）。 */
    private boolean gameRunning;

    /** 抑制下拉框事件：程序自己改选中项时不要触发一次多余的重新加载。 */
    private boolean suppressComboEvent;

    /** 通过「浏览…」选进来的存档路径；为 null 表示没有自定义存档。 */
    private Path customPath;

    private final JComboBox<SaveItem> saveCombo = new JComboBox<>();
    private final JLabel statusDot = Theme.label("", Theme.MUTED, Theme.FONT_SMALL);
    private final JLabel statusDetail = Theme.label("", Theme.MUTED, Theme.FONT_SMALL);
    private final JLabel bannerLabel = Theme.label("", Theme.DANGER, Theme.FONT);
    /** 通宝余额只读展示（通宝只能靠游戏内的签到与副本掉落增长，不提供直接改动）。 */
    private final JLabel tongbaoValue = Theme.label("—", Theme.GOLD, Theme.FONT_TITLE);
    /** 今天副本额度的一行状态：已领 / 上限。 */
    private final JLabel dropNote = Theme.label("", Theme.MUTED, Theme.FONT_SMALL);
    /** 唯一的通宝动作：把今天的副本额度一次补满。 */
    private final JButton dropClaim = Theme.primary("补满今日额度");
    private final JTextField demonField = Theme.numberField(9);
    private final JButton demonSave = Theme.primary("保存");
    /** 魔渊渊晶余额只读展示 + 本周拉满按钮。 */
    private final JLabel abyssValue = Theme.label("—", Theme.GOLD, Theme.FONT_TITLE);
    private final JLabel abyssNote = Theme.label("", Theme.MUTED, Theme.FONT_SMALL);
    private final JButton abyssFill = Theme.primary("本周拉满");
    /** 角色加成块的状态行：当前角色与等级（跟着选中角色变）。 */
    private final JLabel charNote = Theme.label("", Theme.MUTED, Theme.FONT_SMALL);
    /** 直升等级的目标输入框。 */
    private final JTextField levelField = Theme.numberField(4);
    private final JButton levelUp = Theme.primary("升级");
    private final JButton unlockDiff = Theme.ghost("解锁副本难度");
    private final JButton maxPool = Theme.ghost("经验池拉满");
    /** 掉落保底：把 BOSS 保底计数推到「差一次必掉」。 */
    private final JButton pityFill = Theme.ghost("BOSS 保底推满");
    private final JButton browseButton = Theme.ghost("浏览…");
    private final JButton refreshButton = Theme.ghost("刷新");
    private final JButton restoreButton = Theme.ghost("还原备份");
    private final StackPanel charList = new StackPanel(null);
    private final JLabel charTitle = Theme.label("角色卡槽（点击切换）", Theme.MUTED, Theme.FONT_SMALL);
    private final JLabel gridTitle = Theme.label("背包", Theme.TEXT, Theme.FONT_TITLE);
    private final JTextField searchField = Theme.searchField(18, "在当前页签内搜索");
    private final JButton addButton = Theme.primary("+ 添加物品");
    /** 背包分区页签：道具 / 材料，口径与游戏背包分类一致（装备不带数量，不提供页签）。 */
    private final SectionTabs sectionTabs = new SectionTabs(
            new SectionTabs.Tab(ItemCatalog.SECTION_ITEMS, "道具"),
            new SectionTabs.Tab(ItemCatalog.SECTION_MATERIALS, "材料"),
            new SectionTabs.Tab(ItemCatalog.SECTION_EQUIPMENT, "装备"));
    /** 当前页签的分区键。 */
    private String activeSection = ItemCatalog.SECTION_ITEMS;
    private final WrapPanel grid = new WrapPanel();
    /** 网格外层滚动面板：切页签时要把滚动条拉回顶部。 */
    private JScrollPane gridScroll;
    private final JLabel selectedLabel = Theme.label("未选中物品", Theme.MUTED, Theme.FONT);
    private final JTextField itemCountField = Theme.numberField(9);
    /** 编辑栏的动作提示（道具/材料是「数量改为」，装备是「强化等级改为」）。 */
    private final JLabel actionLabel = Theme.label("数量改为", Theme.MUTED, Theme.FONT_SMALL);
    private final JButton itemSave = Theme.primary("保存修改");

    /** 日志区用 JTextPane 而不是 JTextArea：需要按级别上色（成功绿、失败红、提示灰）。 */
    private final JTextPane logPane = new JTextPane();

    /**
     * 构造主窗口：装配界面 → 绑定事件 → 启动游戏探测 → 扫描并自动打开最新存档。
     */
    public MainFrame() {
        super(TITLE);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1080, 700));
        setSize(1320, 840);
        setLocationRelativeTo(null);
        // 窗口左上角和任务栏的图标（exe 文件本身的图标由 jpackage --icon 决定）
        setIconImage(Theme.windowIcon());
        charList.setLayout(new BoxLayout(charList, BoxLayout.Y_AXIS));
        charList.setBackground(Theme.BG);
        try {
            icons = IconRepository.open();
        } catch (IOException e) {
            logWarn("图标资源加载失败：" + e.getMessage() + "（背包格子会显示占位图）");
        }
        buildUi();
        wireEvents();
        startGameWatcher();
        reloadSaves();
        // 首次扫描后如果一份存档都没有，状态点要提示「请先选择并读取存档」
        updateStatus();
    }

    // ---------------------------------------------------------------- 界面搭建

    /**
     * 组装整个窗口：上（存档条 + 锁定横幅）、中（左货币/角色 + 右背包）、下（日志）。
     */
    private void buildUi() {
        JPanel root = Theme.panel(Theme.BG);
        root.setLayout(new BorderLayout());

        JPanel north = Theme.panel(Theme.BG);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(buildTopBar());
        north.add(buildBanner());
        root.add(north, BorderLayout.NORTH);

        JPanel body = Theme.padded(Theme.BG, 0, 14, 0, 14);
        body.setLayout(new BorderLayout(14, 0));
        body.add(buildLeftPanel(), BorderLayout.WEST);
        body.add(buildRightPanel(), BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);

        root.add(buildLogPanel(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    /**
     * 顶部存档条：存档下拉 + 刷新 + 浏览…，右侧是状态点与代数/密钥信息。
     *
     * @return 顶栏组件
     */
    private JComponent buildTopBar() {
        JPanel bar = Theme.padded(Theme.SURFACE, 12, 16, 12, 16);
        bar.setLayout(new BorderLayout(12, 0));

        JPanel left = Theme.panel(Theme.SURFACE);
        left.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.add(Theme.label("存档", Theme.MUTED, Theme.FONT_SMALL));

        saveCombo.setPreferredSize(new Dimension(340, 30));
        saveCombo.setFont(Theme.FONT);
        saveCombo.setBackground(Theme.CARD);
        saveCombo.setForeground(Theme.TEXT);
        // Metal 默认渲染器不认自设配色，这里自己画，才能和深色主题协调
        saveCombo.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, selected, focused);
                label.setFont(Theme.FONT);
                label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
                label.setBackground(selected ? Theme.ACCENT : Theme.CARD);
                label.setForeground(selected ? Color.WHITE : Theme.TEXT);
                label.setOpaque(true);
                return label;
            }
        });
        left.add(saveCombo);

        refreshButton.setToolTipText("重新扫描存档目录，保留当前选择");
        left.add(refreshButton);

        browseButton.setToolTipText("加载别的位置的 save.dat（例如从另一台电脑拷过来的存档）");
        left.add(browseButton);

        restoreButton.setToolTipText("从 backup 里的时间戳备份点整份还原（存档 + 水位 + 注册表）");
        left.add(restoreButton);

        JLabel versionLabel = Theme.label(APP_VERSION, Theme.GOLD,
                Theme.FONT_TITLE.deriveFont(Font.BOLD, 17f));
        versionLabel.setToolTipText("修改器版本：" + APP_VERSION
                + "（适配游戏 1.2.0，写档保持代数不变，含完整备份/还原，并自动同步水位与清理机器封禁）");
        versionLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 2));
        left.add(versionLabel);
        bar.add(left, BorderLayout.WEST);

        JPanel right = Theme.panel(Theme.SURFACE);
        right.setLayout(new FlowLayout(FlowLayout.RIGHT, 12, 4));
        right.add(statusDot);
        right.add(statusDetail);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /**
     * 锁定横幅：游戏运行时显示一行醒目红字，退出后自动消失。
     *
     * @return 横幅组件
     */
    private JComponent buildBanner() {
        JPanel banner = Theme.padded(Theme.BG, 6, 16, 6, 16);
        banner.setLayout(new BorderLayout());
        banner.add(bannerLabel, BorderLayout.WEST);
        bannerLabel.setVisible(false);
        return banner;
    }

    /**
     * 左侧面板：上半是账户货币（通宝/魔点），下半是可滚动的角色卡槽列表。
     *
     * @return 左侧面板
     */
    private JComponent buildLeftPanel() {
        JPanel left = Theme.panel(Theme.BG);
        left.setLayout(new BorderLayout(0, 10));
        left.setPreferredSize(new Dimension(300, 0));

        JPanel currency = Theme.padded(Theme.SURFACE, 14, 14, 12, 14);
        currency.setLayout(new BoxLayout(currency, BoxLayout.Y_AXIS));
        currency.setAlignmentX(Component.LEFT_ALIGNMENT);

        currency.add(Theme.label("账户货币", Theme.TEXT, Theme.FONT_TITLE));
        currency.add(Box.createVerticalStrut(10));
        currency.add(tongbaoBlock());
        currency.add(Box.createVerticalStrut(12));
        currency.add(currencyRow("魔点", Theme.ACCENT, demonField, demonSave));
        currency.add(Box.createVerticalStrut(12));
        currency.add(abyssBlock());

        // 左栏上半 = 货币区 + 角色加成区（都跟着存档/角色刷新），下半 = 角色卡槽列表
        Box leftTop = Box.createVerticalBox();
        leftTop.add(currency);
        leftTop.add(Box.createVerticalStrut(10));
        leftTop.add(charBlock());
        left.add(leftTop, BorderLayout.NORTH);

        JPanel charPanel = Theme.panel(Theme.BG);
        charPanel.setLayout(new BorderLayout(0, 8));
        charTitle.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));
        charPanel.add(charTitle, BorderLayout.NORTH);
        // 用 StackPanel 包一层：滚动时卡片宽度始终跟随视口，不会被挤成一条
        StackPanel holder = new StackPanel(new BorderLayout());
        holder.add(charList, BorderLayout.NORTH);
        JScrollPane scroll = Theme.style(new JScrollPane(holder));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        charPanel.add(scroll, BorderLayout.CENTER);
        left.add(charPanel, BorderLayout.CENTER);
        return left;
    }

    /**
     * 造一行「名称 + 输入框 + 保存按钮」。
     *
     * @param name   货币名
     * @param color  名称颜色
     * @param field  数值输入框
     * @param button 保存按钮
     * @return 行组件
     */
    private JComponent currencyRow(String name, Color color, JTextField field, JButton button) {
        JPanel row = Theme.panel(Theme.SURFACE);
        row.setLayout(new BorderLayout(8, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel label = Theme.label(name, color, Theme.FONT_TITLE);
        // 名称按字数留宽，免得被截断
        label.setPreferredSize(new Dimension(Math.max(46, name.length() * 18), 28));
        row.add(label, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        button.setPreferredSize(new Dimension(64, 30));
        row.add(button, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return row;
    }

    /**
     * 通宝块：余额只读展示 + 唯一的动作「补满今日副本额度」。
     *
     * <p>通宝有账本校验，凭空加钱会写出游戏一读就报 {@code tongbao_ledger} 的存档，
     * 所以这里只保留一条完全走游戏正常渠道的路：把「今天副本还没刷满的额度」一次补上，
     * 记进当天的掉落明细，既不多也不少。余额本身只能靠签到与副本掉落增长。
     *
     * @return 组件
     */
    private JComponent tongbaoBlock() {
        JPanel box = Theme.panel(Theme.SURFACE);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel head = Theme.panel(Theme.SURFACE);
        head.setLayout(new BorderLayout(8, 0));
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(Theme.label("通宝", Theme.GOLD, Theme.FONT_TITLE), BorderLayout.WEST);
        tongbaoValue.setHorizontalAlignment(SwingConstants.RIGHT);
        head.add(tongbaoValue, BorderLayout.CENTER);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        box.add(head);

        box.add(Box.createVerticalStrut(6));
        dropNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(dropNote);

        box.add(Box.createVerticalStrut(6));
        dropClaim.setFont(Theme.FONT_SMALL);
        dropClaim.setAlignmentX(Component.LEFT_ALIGNMENT);
        dropClaim.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        dropClaim.setToolTipText("把今天副本还没刷满的额度一次记进掉落账本（每天上限 "
                + HellTongbao.DAILY_CAP + "）");
        box.add(dropClaim);
        return box;
    }

    /**
     * 渊晶块：余额只读展示 + 「本周拉满」，把本周还没拿到的周额度一次补满。
     */
    private JComponent abyssBlock() {
        JPanel box = Theme.panel(Theme.SURFACE);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel head = Theme.panel(Theme.SURFACE);
        head.setLayout(new BorderLayout(8, 0));
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(Theme.label("渊晶", Theme.GOLD, Theme.FONT_TITLE), BorderLayout.WEST);
        abyssValue.setHorizontalAlignment(SwingConstants.RIGHT);
        head.add(abyssValue, BorderLayout.CENTER);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        box.add(head);

        box.add(Box.createVerticalStrut(6));
        abyssNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(abyssNote);

        box.add(Box.createVerticalStrut(6));
        abyssFill.setFont(Theme.FONT_SMALL);
        abyssFill.setAlignmentX(Component.LEFT_ALIGNMENT);
        abyssFill.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        abyssFill.setToolTipText("把本周渊晶进度补满到每周上限，余额同额增加（周一刷新）");
        box.add(abyssFill);
        return box;
    }

    /**
     * 角色加成块：借鉴游戏「上帝之手」里对正常存档也成立的几项 ——
     * 直升等级（技能点照游戏规则发放）、解锁副本难度、灵兽经验池拉满。
     *
     * <p>这些都是改完读档即生效、且不会触碰账本核账的功能；
     * 无限通宝、无敌、赐新灵动物是上帝档专属，搬不进正常存档。
     *
     * @return 组件
     */
    private JComponent charBlock() {
        JPanel box = Theme.panel(Theme.SURFACE);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);

        charNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(charNote);
        box.add(Box.createVerticalStrut(6));

        JPanel levelRow = Theme.panel(Theme.SURFACE);
        levelRow.setLayout(new BorderLayout(8, 0));
        levelRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        levelRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        levelRow.add(levelField, BorderLayout.CENTER);
        levelUp.setPreferredSize(new Dimension(64, 30));
        levelUp.setToolTipText("直升到目标等级（上限 85）；已进阶的角色按游戏规则补发技能点，"
                + "没进阶过的与游戏行为一致、不发");
        levelRow.add(levelUp, BorderLayout.EAST);
        box.add(levelRow);

        box.add(Box.createVerticalStrut(6));
        JPanel actionRow = Theme.panel(Theme.SURFACE);
        actionRow.setLayout(new GridLayout(1, 2, 6, 0));
        actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        unlockDiff.setFont(Theme.FONT_SMALL);
        unlockDiff.setToolTipText("把打过的副本全部顶到「英雄」难度（照抄游戏上帝之手的规则）；"
                + "没打过的副本存档里没有记录，加不进去");
        maxPool.setFont(Theme.FONT_SMALL);
        maxPool.setToolTipText("把灵兽储存经验池顶到上限 40 亿（等价游戏里的「赐灵兽经验」）");
        actionRow.add(unlockDiff);
        actionRow.add(maxPool);
        box.add(actionRow);

        box.add(Box.createVerticalStrut(6));
        JPanel pityRow = Theme.panel(Theme.SURFACE);
        pityRow.setLayout(new BorderLayout(0, 0));
        pityRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        pityRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        pityFill.setFont(Theme.FONT_SMALL);
        pityFill.setToolTipText("把掉落保底计数推到「保底次数 − 1」，下一次击杀对应 BOSS / 通关伏魔塔必掉；"
                + "霸王卷轴按「连续 300 次没出必出、每个角色只能出一次」处理，已拥有霸王虎或卷轴的角色不再推；没打过的也会补一条计数");
        pityRow.add(pityFill, BorderLayout.CENTER);
        box.add(pityRow);
        return box;
    }

    /**
     * 右侧面板：顶部工具栏（标题 + 搜索 + 添加物品）、中间背包网格、底部单件改数量。
     *
     * @return 右侧面板
     */
    private JComponent buildRightPanel() {
        JPanel right = Theme.panel(Theme.BG);
        right.setLayout(new BorderLayout(0, 10));

        JPanel toolbar = Theme.padded(Theme.SURFACE, 10, 14, 10, 14);
        toolbar.setLayout(new BorderLayout(10, 8));
        JPanel titleBox = Theme.panel(Theme.SURFACE);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.add(gridTitle);
        gridTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        toolbar.add(titleBox, BorderLayout.WEST);

        JPanel tools = Theme.panel(Theme.SURFACE);
        tools.setLayout(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        searchField.setPreferredSize(new Dimension(190, 30));
        tools.add(searchField);
        addButton.setText("添加物品");
        addButton.setPreferredSize(new Dimension(112, 30));
        tools.add(addButton);
        toolbar.add(tools, BorderLayout.EAST);

        // 分区页签单独一行：和游戏背包的分类 tab 对位，切页签只换数据源不重读存档
        JPanel tabsRow = Theme.panel(Theme.SURFACE);
        tabsRow.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 4));
        tabsRow.add(sectionTabs);
        toolbar.add(tabsRow, BorderLayout.SOUTH);
        right.add(toolbar, BorderLayout.NORTH);

        grid.setBackground(Theme.BG);
        JScrollPane gridScroll = Theme.style(new JScrollPane(grid));
        this.gridScroll = gridScroll;
        gridScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        gridScroll.getViewport().setBackground(Theme.BG);
        right.add(gridScroll, BorderLayout.CENTER);

        JPanel bottom = Theme.padded(Theme.SURFACE, 10, 14, 10, 14);
        bottom.setLayout(new BorderLayout(12, 0));
        bottom.add(selectedLabel, BorderLayout.CENTER);

        JPanel actions = Theme.panel(Theme.SURFACE);
        actions.setLayout(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(actionLabel);
        actions.add(itemCountField);
        actions.add(itemSave);
        bottom.add(actions, BorderLayout.EAST);
        right.add(bottom, BorderLayout.SOUTH);
        return right;
    }

    /**
     * 底部日志区：彩色只读文本，自动滚到最新一行。
     *
     * @return 日志面板
     */
    private JComponent buildLogPanel() {
        JPanel panel = Theme.padded(Theme.SURFACE, 8, 16, 10, 16);
        panel.setLayout(new BorderLayout(0, 6));
        panel.setPreferredSize(new Dimension(0, 132));
        panel.add(Theme.label("操作日志", Theme.MUTED, Theme.FONT_SMALL), BorderLayout.NORTH);

        logPane.setEditable(false);
        logPane.setBackground(new Color(0x05, 0x10, 0x26));
        logPane.setForeground(new Color(0xB6, 0xC8, 0xE2));
        // 别用 Consolas：它没有中文字形，中文会显示成方块
        logPane.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        logPane.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JScrollPane scroll = Theme.style(new JScrollPane(logPane));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    // ---------------------------------------------------------------- 事件

    /**
     * 绑定所有控件的事件。
     */
    private void wireEvents() {
        // 下拉切换 = 打开对应存档（程序自己改选中项时用 suppressComboEvent 挡掉）
        saveCombo.addActionListener(e -> {
            if (suppressComboEvent) {
                return;
            }
            SaveItem item = (SaveItem) saveCombo.getSelectedItem();
            if (item != null) {
                openSave(item.path(), -1, null);
            }
        });

        refreshButton.addActionListener(e -> {
            reloadSaves();
            log("已重新扫描存档目录");
        });
        browseButton.addActionListener(e -> browseSave());
        restoreButton.addActionListener(e -> restoreBackup());

        dropClaim.addActionListener(e -> claimTodayDrop());
        abyssFill.addActionListener(e -> fillAbyssWeekly());
        levelUp.addActionListener(e -> raiseLevel());
        unlockDiff.addActionListener(e -> unlockDifficulties());
        maxPool.addActionListener(e -> fillShenshouPool());
        pityFill.addActionListener(e -> openPityFill());
        demonSave.addActionListener(e -> saveDemon());
        itemSave.addActionListener(e -> saveItemCount());
        addButton.addActionListener(e -> addItems());

        // 切页签：只换数据源，清掉跨分区的选中，避免底部「数量改为」指向看不见的东西
        sectionTabs.onChange(key -> {
            activeSection = key;
            selectedItem = null;
            applySection();
            updateSelectedBar();
        });

        // 搜索框边打边过滤（背包规模在百级，不需要防抖）
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshGrid();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshGrid();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshGrid();
            }
        });
    }

    /**
     * 启动游戏进程轮询；同时立刻探一次，避免启动那一两秒的窗口期漏判。
     */
    private void startGameWatcher() {
        Timer timer = new Timer(WATCH_INTERVAL, e -> checkGameState());
        timer.setRepeats(true);
        timer.start();
        SwingUtilities.invokeLater(this::checkGameState);
    }

    // ---------------------------------------------------------------- 存档加载

    /**
     * 重新扫描存档目录并重建下拉框。
     *
     * <p>会保留两样东西：
     * <ul>
     *   <li>用户通过「浏览…」选进来的自定义存档（排在列表最前面）；</li>
     *   <li>当前正在看的那份存档（扫描完仍然选中它，不会莫名跳回最新档）。</li>
     * </ul>
     */
    private void reloadSaves() {
        List<Path> found;
        try {
            found = SaveLocator.listAll();
        } catch (SaveFormatException e) {
            // 目录不存在不算致命错误：用户可能压根没装游戏，而是拿别的机器的存档来改，
            // 这时还可以用「浏览…」手选文件，所以只提示不中断。
            found = List.of();
            logWarn("扫描存档目录失败：" + e.getMessage());
        }

        Path previous = currentPath();
        List<SaveItem> items = new ArrayList<>();
        if (customPath != null && Files.isRegularFile(customPath)) {
            items.add(new SaveItem(customPath, describeCustom(customPath)));
        }
        for (Path path : found) {
            if (customPath != null && samePath(path, customPath)) {
                continue; // 自定义存档恰好也在标准目录里，别重复列一遍
            }
            items.add(new SaveItem(path, describe(path)));
        }

        suppressComboEvent = true;
        saveCombo.removeAllItems();
        for (SaveItem item : items) {
            saveCombo.addItem(item);
        }

        if (items.isEmpty()) {
            suppressComboEvent = false;
            logWarn("没有找到任何 save.dat，可用「浏览…」手动选择一份存档");
            updateStatus();
            return;
        }

        // 优先回到原来那份；找不到就选最新的一份（列表已按修改时间倒序）
        int target = 0;
        if (previous != null) {
            for (int i = 0; i < items.size(); i++) {
                if (samePath(items.get(i).path(), previous)) {
                    target = i;
                    break;
                }
            }
        }
        saveCombo.setSelectedIndex(target);
        suppressComboEvent = false;
        openSave(items.get(target).path(), -1, null);
    }

    /**
     * 打开文件选择器加载任意位置的 save.dat。
     *
     * <p>典型场景：把别人的存档（连同账号目录）拷到自己机器上改。
     * 这时存档不在 {@code %APPDATA%} 下，扫描列表里看不到，只能手动选。
     */
    private void browseSave() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 save.dat");
        chooser.setFileFilter(new FileNameExtensionFilter("功夫派存档 (save.dat)", "dat"));
        try {
            chooser.setCurrentDirectory(SaveLocator.savesRoot().toFile());
        } catch (SaveFormatException ignored) {
            // 取不到标准目录就交给系统默认目录，不影响选择
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path picked = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (!Files.isRegularFile(picked)) {
            showError("文件不存在", picked + "\n请重新选择。");
            return;
        }
        customPath = picked;
        log("已选择自定义存档：" + picked);
        reloadSaves();
    }

    /**
     * 从备份点还原：选择 backup 下的时间戳目录，整份覆盖存档、水位与注册表。
     */
    private void restoreBackup() {
        if (gameRunning) {
            showError("游戏运行中", "请先完全退出游戏，再执行还原。");
            return;
        }
        Path target = currentPath();
        if (target == null) {
            SaveItem item = (SaveItem) saveCombo.getSelectedItem();
            if (item != null) {
                target = item.path();
            }
        }
        if (target == null) {
            showError("没有可还原的目标存档", "请先在顶部选择一份存档。");
            return;
        }

        List<Path> backups = WatermarkSnapshot.listBackups(target);
        if (backups.isEmpty()) {
            showError("没有可用的备份点", "该存档的 backup 目录里还没有时间戳备份。");
            return;
        }

        String[] names = backups.stream()
                .map(p -> p.getFileName().toString())
                .toArray(String[]::new);
        JComboBox<String> combo = new JComboBox<>(names);
        int picked = JOptionPane.showOptionDialog(this, combo, "选择备份点",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, null, null);
        if (picked != JOptionPane.OK_OPTION || combo.getSelectedIndex() < 0) {
            return;
        }

        Path snapshotDir = backups.get(combo.getSelectedIndex());
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定还原到备份点 " + snapshotDir.getFileName() + " 吗？\n\n"
                        + "这会覆盖当前存档、水位记录和注册表缓存。",
                "确认还原", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            WatermarkSnapshot.restore(snapshotDir, target);
            service = null;
            reloadSaves();
            updateStatus();
            log("已从备份点 " + snapshotDir.getFileName() + " 还原");
        } catch (IOException | InterruptedException e) {
            showError("还原失败", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 生成下拉框里的一行文案：账号 · 时间 · 大小。
     *
     * @param path 存档路径
     * @return 文案
     */
    private static String describe(Path path) {
        String id = path.getParent() == null ? "?" : path.getParent().getFileName().toString();
        String time;
        try {
            time = SAVE_TIME.format(Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()));
        } catch (IOException e) {
            time = "未知时间";
        }
        long kilobytes = 0;
        try {
            kilobytes = Files.size(path) / 1024;
        } catch (IOException ignored) {
            // 大小读不到就显示 0，不影响读取
        }
        return "账号 %s · %s · %d KB".formatted(id, time, kilobytes);
    }

    /**
     * 自定义存档的文案（前缀区分，避免和标准目录里的同名项混淆）。
     *
     * @param path 存档路径
     * @return 文案
     */
    private static String describeCustom(Path path) {
        Path parent = path.getParent();
        String folder = parent == null ? "" : parent.getFileName().toString();
        return "%s · %s\\%s".formatted(CUSTOM_PREFIX, folder, path.getFileName());
    }

    /**
     * 判断两个路径是否指向同一个文件。
     *
     * @param a 路径 A
     * @param b 路径 B
     * @return 相同返回 true
     */
    private static boolean samePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /**
     * 当前正在查看的存档路径（未打开任何存档时为 null）。
     *
     * @return 路径
     */
    private Path currentPath() {
        return service == null ? null : service.path();
    }

    /**
     * 打开存档并刷新整个界面。
     *
     * @param path     存档路径
     * @param keepSlot 需要保持选中的卡槽（-1 表示自动选第一个）
     * @param keepItem 需要保持选中的物品（可为 null）
     */
    private void openSave(Path path, int keepSlot, ItemView keepItem) {
        setBusy(true);
        try {
            service = SaveService.open(path, catalog);
            keyInfo = SaveKeys.resolve(path);
            currentSlot = -1;
            selectedItem = null;
            rebuildCharacters(keepSlot);
            refreshCurrencies();
            updateStatus();
            if (keepItem != null) {
                reselect(keepItem);
            }
            long gen;
            try {
                gen = service.generation();
            } catch (SaveFormatException ignored) {
                gen = -1;
            }
            log(gen >= 0
                    ? "已读取存档 " + path.getFileName() + "（代数 " + gen + "）"
                    : "已读取存档 " + path.getFileName());
        } catch (SaveFormatException e) {
            logError("读取失败：" + oneLine(e.getMessage()));
            showError("读取存档失败", e.getMessage());
        } catch (IOException e) {
            logError("读取失败：" + e.getMessage());
            showError("读取存档失败", "文件读写异常：\n" + e.getMessage());
        } finally {
            setBusy(false);
        }
    }

    /**
     * 重建左侧角色卡片列表，并按 {@code keepSlot} 选中对应角色。
     *
     * @param keepSlot 目标卡槽；-1 或不存在时退回第一个角色
     */
    private void rebuildCharacters(int keepSlot) {
        charList.removeAll();
        cards.clear();
        List<CharacterView> characters = service.characters();
        if (characters.isEmpty()) {
            charTitle.setText("角色卡槽");
            gridTitle.setText("这份存档里没有角色");
            refreshGrid();
            return;
        }
        // 卡片标题带个数，方便确认读到的角色是不是齐全（存档里空槽会被跳过）
        charTitle.setText("角色卡槽（共 %d 个 · 点击切换）".formatted(characters.size()));
        for (CharacterView character : characters) {
            CharacterCard card = new CharacterCard(character, this);
            card.setEditingEnabled(!gameRunning);
            cards.put(character.slot(), card);
            charList.add(card);
            charList.add(Box.createVerticalStrut(8));
        }
        charList.add(Box.createVerticalGlue());
        charList.revalidate();
        charList.repaint();

        int target = cards.containsKey(keepSlot) ? keepSlot : characters.get(0).slot();
        selectSlot(target);
    }

    /**
     * 刷新货币区：通宝余额只读、今日副本额度状态、以及「补满」按钮的可用性。
     */
    private void refreshCurrencies() {
        if (service == null) {
            tongbaoValue.setText("—");
            demonField.setText("");
            abyssValue.setText("—");
        } else {
            Currencies currencies = service.currencies();
            tongbaoValue.setText(Long.toString(currencies.tongbao()));
            demonField.setText(Long.toString(currencies.demonPoints()));
            SaveService.AbyssWeekly abyss = service.abyssWeekly();
            abyssValue.setText(Long.toString(abyss.crystal()));
            if (!currencies.dropDetailConsistent()) {
                logWarn("掉落明细合计 %d 与总额 %d 不一致，建议先把存档修好再领额度"
                        .formatted(currencies.dropSum(), currencies.drop()));
            }
        }
        updateDropClaim();
        updateAbyssClaim();
    }

    /**
     * 按「今天有没有掉落记录 / 还剩多少额度 / 游戏是否在跑」决定按钮文案与可用性。
     *
     * <p>存档只能做原地补丁，写不进存档里不存在的日期键，所以今天没在副本里掉过通宝，
     * 就没有可补的额度 —— 这时候按钮是灰的，并把原因写在上面的小字里。
     */
    private void updateDropClaim() {
        boolean ready = !gameRunning && service != null;
        if (service == null) {
            dropNote.setText("");
            dropClaim.setText("补满今日额度");
            setLockable(dropClaim, false, Theme.ACCENT);
            return;
        }
        SaveService.TodayDrop today = service.todayDrop();
        String note;
        if (!today.recorded()) {
            note = "今日副本额度 0 / " + HellTongbao.DAILY_CAP + "（先进游戏刷一次）";
            ready = false;
        } else {
            note = "今日副本额度 " + today.used() + " / " + HellTongbao.DAILY_CAP;
            if (today.free() <= 0) {
                ready = false;
            }
        }
        dropNote.setText(note);
        if (today.free() <= 0) {
            dropClaim.setText("今日额度已领满");
        } else {
            dropClaim.setText("补满今日额度 +" + today.free());
        }
        setLockable(dropClaim, ready, Theme.ACCENT);
    }

    /**
     * 按「存档是否已有渊晶字段 / 本周是否已满」决定渊晶按钮文案与可用性。
     */
    private void updateAbyssClaim() {
        if (service == null) {
            abyssNote.setText("");
            abyssFill.setText("本周拉满");
            setLockable(abyssFill, false, Theme.ACCENT);
            return;
        }
        SaveService.AbyssWeekly abyss = service.abyssWeekly();
        long free = Math.max(0, abyss.cap() - abyss.weekCrystal());
        abyssNote.setText("本周 " + abyss.weekCrystal() + " / " + abyss.cap());
        if (free <= 0) {
            abyssFill.setText("本周已拉满");
        } else {
            abyssFill.setText("本周拉满 +" + free);
        }
        setLockable(abyssFill, !gameRunning && free > 0, Theme.ACCENT);
    }

    /**
     * 更新顶部状态：三态文案 + 代数/密钥来源。
     *
     * <p>原来的实现只有一个"代数"标签，用户看不出「现在能不能改」，
     * 所以这里把三种状态显式写出来：游戏运行中 / 可安全修改 / 未读取存档。
     */
    private void updateStatus() {
        if (gameRunning) {
            statusDot.setForeground(Theme.DANGER);
            statusDot.setText("● 游戏正在运行中！修改功能已锁定，请退出游戏");
        } else if (service != null) {
            statusDot.setForeground(Theme.OK);
            statusDot.setText("● 游戏未运行，可安全修改");
        } else {
            statusDot.setForeground(Theme.MUTED);
            statusDot.setText("● 请先选择并读取存档");
        }

        if (service != null && keyInfo != null) {
            String generation;
            try {
                generation = Long.toString(service.generation());
            } catch (SaveFormatException e) {
                generation = "?";
            }
            SaveMeta meta = service.meta();
            AntiCheatState anti = service.antiCheat();
            // 反作弊状态只在被标记时才用警示色，平时保持灰，免得天天吓人
            statusDetail.setForeground(anti.clean() ? Theme.MUTED : Theme.DANGER);
            statusDetail.setText("代数 %s · 密钥 %s · 结构 v%d · 反作弊 %s".formatted(
                    generation, keyInfo.source().label(), meta.schemaVersion(), anti.summary()));
        } else {
            statusDetail.setText("");
        }
    }

    // ---------------------------------------------------------------- 角色与背包

    /**
     * 角色卡被点击：切换当前角色。
     *
     * @param slot 卡槽号
     */
    @Override
    public void onSelect(int slot) {
        selectSlot(slot);
    }

    /**
     * 角色卡里的「保存」被点击：写功夫豆。
     *
     * @param slot  卡槽号
     * @param value 新值
     */
    @Override
    public void onSaveBean(int slot, long value) {
        if (guardGameRunning() || service == null) {
            return;
        }
        if (value < 0) {
            showError("数值不对", "功夫豆不能为负数");
            return;
        }
        try {
            service.setBean(slot, value);
            SaveOutcome outcome = persist();
            logOk("功夫豆（卡槽 %d）→ %d，代数 %d%s"
                    .formatted(slot, value, outcome.generation(), verifiedNote(outcome)));
            // 回读并停留在原角色，界面立刻反映磁盘上的真实值
            reloadAfterWrite(slot, null);
        } catch (SaveFormatException e) {
            showError("改功夫豆失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    /**
     * 切换到某个角色：更新背包网格、选中态与功夫豆输入框。
     *
     * @param slot 卡槽号
     */
    private void selectSlot(int slot) {
        if (service == null || !cards.containsKey(slot)) {
            return;
        }
        currentSlot = slot;
        cards.forEach((key, card) -> card.setSelected(key == slot));
        CharacterView character = service.character(slot);
        if (character == null) {
            return;
        }
        gridTitle.setText("%s 的背包（%d 件）".formatted(character.name(), character.itemCount()));
        // 三个分区各存一份，页签与网格都从这里取数
        slotItems = new ArrayList<>(character.items());
        slotMaterials = new ArrayList<>(character.materials());
        slotEquipment = new ArrayList<>(character.equipment());
        sectionTabs.setCount(ItemCatalog.SECTION_ITEMS, slotItems.size());
        sectionTabs.setCount(ItemCatalog.SECTION_MATERIALS, slotMaterials.size());
        sectionTabs.setCount(ItemCatalog.SECTION_EQUIPMENT, slotEquipment.size());
        selectedItem = null;
        applySection();
        updateSelectedBar();
        // 商城解锁进度是「按角色」存的，切角色必须跟着换一份
        cards.get(slot).setBean(character.bean());
        refreshCharBlock();
    }

    /**
     * 应用当前分区页签：切换数据源、刷新标题与网格、同步「添加」按钮的可用状态。
     *
     * <p>页签只是换引用，不重新读存档，所以来回切是瞬时的。
     */
    private void applySection() {
        currentItems = switch (activeSection) {
            case ItemCatalog.SECTION_MATERIALS -> slotMaterials;
            case ItemCatalog.SECTION_EQUIPMENT -> slotEquipment;
            default -> slotItems;
        };
        CharacterView character = service == null || currentSlot < 0 ? null : service.character(currentSlot);
        if (character == null) {
            gridTitle.setText("背包");
        } else {
            gridTitle.setText("%s 的背包 · %s（%d 件）".formatted(
                    character.name(), sectionLabel(activeSection), currentItems.size()));
        }
        refreshGrid();
        scrollGridToTop();
        updateAddButton();
    }

    /**
     * 把背包网格滚回顶部。
     *
     * <p>切页签/切角色后列表长度会变，留着上一个页签的滚动位置容易让人以为「这页是空的」。
     */
    private void scrollGridToTop() {
        if (gridScroll == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> gridScroll.getViewport().setViewPosition(new Point(0, 0)));
    }

    /**
     * 分区键转显示名。
     *
     * @param section 分区键
     * @return 道具 / 材料
     */
    private static String sectionLabel(String section) {
        return switch (section) {
            case ItemCatalog.SECTION_MATERIALS -> "材料";
            case ItemCatalog.SECTION_EQUIPMENT -> "装备";
            default -> "道具";
        };
    }

    /**
     * 按当前页签同步「添加」按钮：文案跟着页签变，游戏运行时禁用。
     */
    private void updateAddButton() {
        addButton.setText("＋ 添加" + sectionLabel(activeSection));
        addButton.setToolTipText("往「%s」分区里添加物品".formatted(sectionLabel(activeSection)));
        setLockable(addButton, !gameRunning && currentSlot >= 0, Theme.ACCENT);
    }

    /**
     * 按当前角色与搜索关键字重画背包网格。
     */
    private void refreshGrid() {
        grid.removeAll();
        cells.clear();
        if (service == null || currentSlot < 0) {
            grid.revalidate();
            grid.repaint();
            return;
        }
        String keyword = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        for (ItemView item : currentItems) {
            // 名称模糊匹配 + ID 精确包含，两种输入习惯都照顾到
            if (!keyword.isEmpty()
                    && !item.name().toLowerCase().contains(keyword)
                    && !Integer.toString(item.id()).contains(keyword)) {
                continue;
            }
            ItemCell cell = new ItemCell(item,
                    () -> icons == null ? IconRepository.placeholderIcon() : icons.icon(item.id()),
                    this::selectItem);
            if (isSame(selectedItem, item)) {
                cell.setSelected(true);
            }
            grid.add(cell);
            cells.add(cell);
        }
        if (cells.isEmpty()) {
            String text;
            if (currentItems.isEmpty()) {
                text = "「%s」页签是空的".formatted(sectionLabel(activeSection));
            } else {
                text = "本页签没有匹配的物品";
            }
            // 搜索只在当前页签生效，但别的页签有命中时得说一声，否则用户会以为背包里没有
            int elsewhere = countMatchesElsewhere(keyword);
            if (elsewhere > 0) {
                text += "；其他页签还有 %d 件匹配".formatted(elsewhere);
            }
            JLabel empty = Theme.label(text, Theme.MUTED, Theme.FONT);
            empty.setBorder(BorderFactory.createEmptyBorder(24, 12, 0, 0));
            grid.add(empty);
        }
        grid.revalidate();
        grid.repaint();
    }

    /**
     * 统计另一个页签里匹配关键字的件数（用于「要点一下别的页签」的提示）。
     *
     * @param keyword 已转小写的关键字
     * @return 命中件数
     */
    private int countMatchesElsewhere(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (List<ItemView> list : List.of(slotItems, slotMaterials, slotEquipment)) {
            if (list == currentItems) {
                continue;
            }
            for (ItemView item : list) {
                if (item.name().toLowerCase().contains(keyword)
                        || Integer.toString(item.id()).contains(keyword)) {
                    matched++;
                }
            }
        }
        return matched;
    }

    /**
     * 点选一件物品：高亮该格子并带出当前数量。
     *
     * @param item 物品
     */
    private void selectItem(ItemView item) {
        selectedItem = item;
        for (ItemCell cell : cells) {
            cell.setSelected(isSame(cell.item(), item));
        }
        updateSelectedBar();
    }

    /**
     * 刷新底部「已选物品 + 数量」区域；没选中时把输入框和按钮一起锁掉。
     */
    private void updateSelectedBar() {
        boolean equipment = selectedItem != null
                && ItemCatalog.SECTION_EQUIPMENT.equals(selectedItem.section());
        actionLabel.setText(equipment ? "强化等级改为" : "数量改为");
        if (selectedItem == null) {
            selectedLabel.setText(equipment ? "点选一件装备后可改强化" : "点选一件物品后可改数量");
            selectedLabel.setForeground(Theme.MUTED);
            itemCountField.setText("");
            itemCountField.setEnabled(false);
            setLockable(itemSave, false, Theme.ACCENT);
            return;
        }
        if (equipment) {
            if (!catalog.canStrengthen(selectedItem.id())) {
                selectedLabel.setText("已选：%s（#%d）· 首饰与橙色装备无法强化".formatted(
                        selectedItem.name(), selectedItem.id()));
                selectedLabel.setForeground(Theme.TEXT);
                itemCountField.setText("");
                itemCountField.setEnabled(false);
                setLockable(itemSave, false, Theme.ACCENT);
                return;
            }
            selectedLabel.setText("已选：%s（#%d）当前强化 +%d（上限 +%d）%s".formatted(
                    selectedItem.name(), selectedItem.id(), selectedItem.strengthen(),
                    catalog.strengthenCap(selectedItem.id()),
                    selectedItem.worn() ? " · 身上穿着" : ""));
            itemCountField.setText(Long.toString(selectedItem.strengthen()));
        } else {
            SaveService.MaterialCap audit = service.materialCaps().get(selectedItem.id());
            String suffix = "";
            if (audit != null && audit.limited()) {
                long total = service.materialTotal(selectedItem.id());
                suffix = " · 材料审计上限 %d（账号合计 %d）".formatted(audit.cap(), total);
            }
            selectedLabel.setText("已选：%s（#%d）当前 %d%s".formatted(
                    selectedItem.name(), selectedItem.id(), selectedItem.count(), suffix));
            itemCountField.setText(Long.toString(selectedItem.count()));
        }
        selectedLabel.setForeground(Theme.TEXT);
        itemCountField.setEnabled(!gameRunning);
        setLockable(itemSave, !gameRunning, Theme.ACCENT);
    }

    /**
     * 保存单件物品的新数量。
     */
    private void saveItemCount() {
        if (guardGameRunning() || service == null || selectedItem == null) {
            return;
        }
        long value = parseLong(itemCountField.getText());
        if (value < 0) {
            showError("数值不对", "请输入 0 或正整数");
            return;
        }
        try {
            ItemView target = selectedItem;
            if (ItemCatalog.SECTION_EQUIPMENT.equals(target.section())) {
                if (!catalog.canStrengthen(target.id())) {
                    showError("无法强化", "%s是首饰或橙色装备，游戏本身不允许强化这类装备".formatted(target.name()));
                    return;
                }
                int cap = catalog.strengthenCap(target.id());
                if (value > cap) {
                    showError("超出强化上限",
                            "%s的品质强化上限是 +%d（绿4/蓝8/紫12），游戏里到不了 +%d".formatted(
                                    target.name(), cap, value));
                    return;
                }
                int level = service.setStrengthenLevel(currentSlot, target, (int) value);
                SaveOutcome outcome = persist();
                logOk("%s 强化等级 → +%d，代数 %d%s"
                        .formatted(target.name(), level, outcome.generation(), verifiedNote(outcome)));
            } else {
                service.setItemCount(currentSlot, target.section(), target.index(), value);
                SaveOutcome outcome = persist();
                logOk("%s 数量 → %d，代数 %d%s"
                        .formatted(target.name(), value, outcome.generation(), verifiedNote(outcome)));
            }
            reloadAfterWrite(currentSlot, target);
        } catch (SaveFormatException e) {
            showError("修改失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    /**
     * 打开「添加物品」弹窗并执行批量添加。
     *
     * <p>写盘策略与单件修改不同：这里先在内存里一次性把所有勾选项都加进去，
     * 最后只落盘一次。理由是一次会话要重算十几遍 HMAC、还要重写十几遍整个文件，
     * 既慢又白白多出十几份备份；而只要中途有失败，最终那次 {@link #persist()}
     * 也不会写坏——失败项会单独列出来告诉用户。
     */
    private void addItems() {
        if (guardGameRunning() || service == null || currentSlot < 0) {
            return;
        }
        // 只列当前页签这一类的候选：在「材料」页签里点添加，看到的就是材料清单
        List<ItemCatalog.Entry> candidates = catalog.candidates(service.ownedIds(currentSlot)).stream()
                .filter(entry -> activeSection.equals(entry.section()))
                .toList();
        if (candidates.isEmpty()) {
            showInfo("没有可添加的物品",
                    "这个角色已经拥有目录里的全部「%s」。".formatted(sectionLabel(activeSection)));
            return;
        }
        AddItemDialog.Result result = AddItemDialog.open(this, candidates, activeSection, catalog);
        if (result == null) {
            return;
        }
        int added = 0;
        int merged = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (ItemCatalog.Entry entry : result.items()) {
                try {
                    SaveService.AddResult outcome = service.addItem(
                            currentSlot, entry.id(), result.count(), entry.section(),
                            result.strengthen());
                    if (outcome.merged()) {
                        merged++;
                    } else {
                        added++;
                    }
                } catch (SaveFormatException e) {
                    failures.add(entry.name() + "：" + oneLine(e.getMessage()));
                }
            }
            if (added + merged == 0) {
                logError("添加物品失败：" + String.join("；", failures));
                showError("一件都没能添加", String.join("\n", failures));
                return;
            }
            SaveOutcome outcome = persist();
            String character = service.character(currentSlot) == null
                    ? "当前角色" : service.character(currentSlot).name();
            logOk(result.strengthen() > 0
                    ? "添加物品：新增 %d 件、累加 %d 件，每件 %d，初始强化 +%d，代数 %d%s".formatted(
                            added, merged, result.count(), result.strengthen(),
                            outcome.generation(), verifiedNote(outcome))
                    : "添加物品：新增 %d 件、累加 %d 件，每件 %d，代数 %d%s".formatted(
                            added, merged, result.count(), outcome.generation(), verifiedNote(outcome)));
            reloadAfterWrite(currentSlot, null);

            StringBuilder message = new StringBuilder();
            message.append("已向「%s」的背包写入 %d 件物品，每件 ×%d。"
                    .formatted(character, added + merged, result.count()));
            if (merged > 0) {
                message.append("\n其中 %d 件是已有物品，数量已累加。".formatted(merged));
            }
            if (!failures.isEmpty()) {
                message.append("\n\n有 %d 件失败：\n%s".formatted(
                        failures.size(), String.join("\n", failures.subList(0, Math.min(failures.size(), 8)))));
                logWarn("部分失败：" + String.join("；", failures));
            }
            message.append("\n\n下次启动游戏登录后生效。");
            if (failures.isEmpty()) {
                showInfo("添加成功", message.toString());
            } else {
                showError("部分成功", message.toString());
            }
        } catch (SaveFormatException e) {
            showError("添加物品失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 货币

    /**
     * 按当前角色刷新角色加成块（等级、输入框默认值、各按钮可用性）。
     */
    private void refreshCharBlock() {
        boolean ready = !gameRunning && service != null && currentSlot >= 0;
        if (service == null || currentSlot < 0) {
            charNote.setText("先读取存档");
            unlockDiff.setEnabled(false);
            maxPool.setEnabled(false);
            pityFill.setEnabled(false);
            pityFill.setText("BOSS 保底推满");
            levelUp.setEnabled(false);
            levelField.setEnabled(false);
            return;
        }
        CharacterView character = service.character(currentSlot);
        String name = character == null ? "角色" : character.name();
        charNote.setText(name + " · Lv." + service.characterLevel(currentSlot));
        levelField.setText(Integer.toString(
                Math.min(SaveService.MAX_LEVEL, service.characterLevel(currentSlot) + 1)));
        setLockable(levelField, ready);
        setLockable(levelUp, ready, Theme.ACCENT);
        setLockable(unlockDiff, ready && !service.character(currentSlot).materials().isEmpty(),
                Theme.CARD);
        setLockable(maxPool, ready && service.hasShenshouPool(currentSlot), Theme.CARD);

        int pending = service.pityPending(currentSlot);
        pityFill.setText(pending > 0 ? "BOSS 保底推满（" + pending + "）" : "BOSS 保底推满");
        setLockable(pityFill, ready, Theme.CARD);
    }

    /**
     * 直升等级：目标等级必须高于当前等级，技能点由存档层按游戏规则补发。
     */
    private void raiseLevel() {
        if (guardGameRunning() || service == null || currentSlot < 0) {
            return;
        }
        long value = parseLong(levelField.getText());
        if (value <= 0) {
            showError("数值不对", "请输入 1 到 " + SaveService.MAX_LEVEL + " 之间的目标等级");
            return;
        }
        try {
            int current = service.characterLevel(currentSlot);
            int target = (int) Math.min(value, SaveService.MAX_LEVEL);
            if (target <= current) {
                showInfo("不用升", "当前已经是 Lv." + current + "，输入更高的目标等级。");
                return;
            }
            int points = service.setCharacterLevel(currentSlot, target);
            SaveOutcome outcome = persist();
            logOk("%s Lv.%d → Lv.%d（补发技能点 %d），代数 %d%s".formatted(
                    service.character(currentSlot).name(), current, target, points,
                    outcome.generation(), verifiedNote(outcome)));
            refreshCharBlock();
            updateStatus();
            log("属性（血量/攻防）按等级自动换算，读档即生效；经验清零从新等级重新累积");
        } catch (SaveFormatException e) {
            showError("升级失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    /**
     * 解锁副本难度：把已有记录的副本全部顶到「英雄」档。
     */
    private void unlockDifficulties() {
        if (guardGameRunning() || service == null || currentSlot < 0) {
            return;
        }
        try {
            int changed = service.unlockDungeons(currentSlot);
            if (changed == 0) {
                showInfo("没有可解锁的",
                        "这份存档里没有已通关的副本记录，存档补丁写不进新的副本键。\n"
                                + "先在游戏里把想解锁的副本打通任意难度一次。");
                return;
            }
            SaveOutcome outcome = persist();
            logOk("%s：已把 %d 个副本的已通难度顶到「英雄」，代数 %d%s".formatted(
                    service.character(currentSlot).name(), changed,
                    outcome.generation(), verifiedNote(outcome)));
            updateStatus();
            log("没打过的副本不在存档里，仍需在游戏里正常解锁");
        } catch (SaveFormatException e) {
            showError("解锁失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    /**
     * 灵兽经验池拉满（上限 40 亿，与游戏内「赐灵兽经验」同源）。
     */
    private void fillShenshouPool() {
        if (guardGameRunning() || service == null || currentSlot < 0) {
            return;
        }
        try {
            long after = service.maxShenshouPool(currentSlot);
            SaveOutcome outcome = persist();
            logOk("%s：灵兽经验池 → %d，代数 %d%s".formatted(
                    service.character(currentSlot).name(), after,
                    outcome.generation(), verifiedNote(outcome)));
            updateStatus();
            log("在灵兽面板里照常分配给灵兽即可");
        } catch (SaveFormatException e) {
            showError("拉满失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    /**
     * 掉落保底：把保底计数推到「保底次数 − 1」，下一次击杀对应 BOSS / 通关伏魔塔必掉。
     *
     * <p>游戏判定是 {@code 连续未中次数 + 1 >= 保底次数} 就必出、出了清零
     * （{@code globals/drop_pity.gd}、{@code 801-伏魔塔/伏魔塔.gd}），所以推到
     * 「保底 − 1」等价于「再打一次必给」。这是正常玩家刷本可以达到的合法状态，
     * 数值守卫也不检查这两个字段。
     *
     * <p>没打过的 BOSS 会新补一条计数；守护神关卡/小怪那一类查不到上限，一律不动。
     * 伏魔塔奖励保底单独在 {@code fumo_tower_rewards.misses}，固定 10 次保底。
     */
    private void openPityFill() {
        if (guardGameRunning() || service == null || currentSlot < 0) {
            return;
        }
        List<SaveService.PityStatus> all = service.pityStatus(currentSlot);
        SaveService.FumoTowerStatus fumo = service.fumoTowerStatus(currentSlot);
        boolean fumoFillable = fumo.fillable();
        SaveService.BawanghuPity bawang = service.bawanghuPity(currentSlot);
        boolean bawangFillable = bawang.fillable();
        boolean bawangOwned = bawang.owned();
        List<SaveService.PityStatus> todo = new ArrayList<>();
        int done = 0;
        int added = 0;
        int unknown = 0;
        for (SaveService.PityStatus status : all) {
            if (!status.known()) {
                unknown++;
            } else if (status.ready()) {
                done++;
            } else {
                todo.add(status);
                if (!status.present()) {
                    added++;
                }
            }
        }
        if (todo.isEmpty() && !fumoFillable && !bawangFillable) {
            showInfo("没有可推的保底",
                    "这个角色已知的 BOSS 保底（含伏魔塔、霸王卷轴）都已经处于「下一次必掉」状态。"
                            + (bawangOwned ? "\n\n霸王卷轴：这个角色已经拿到过（灵兽栏有霸王虎，"
                            + "或背包里还有一张卷轴），每个角色只能出一次，所以没有再推的意义。"
                            : ""));
            return;
        }

        StringBuilder message = new StringBuilder();
        if (!todo.isEmpty()) {
            message.append("推到「保底次数 − 1」后，下一次击杀对应 BOSS 必掉（出货后游戏自己清零）。\n\n");
            for (SaveService.PityStatus status : todo) {
                message.append("· ").append(status.name())
                        .append("（").append(status.scope()).append("）  ")
                        .append(status.present() ? status.current() : "未打过")
                        .append(" / ").append(status.maxRolls())
                        .append("  →  ").append(status.target())
                        .append(status.present() ? "" : "（新建计数）")
                        .append("\n");
            }
            message.append("\n共 ").append(todo.size()).append(" 项会推到必掉");
            if (done > 0) {
                message.append("，另有 ").append(done).append(" 项本来就在必掉状态");
            }
            message.append("。");
            if (added > 0) {
                message.append("\n其中 ").append(added)
                        .append(" 项你还没打过、存档里没有计数，会新补一条记录（游戏读到就生效）。");
            }
            if (unknown > 0) {
                message.append("\n").append(unknown)
                        .append(" 项查不到保底上限，不做改动。");
            }
        }
        if (fumoFillable) {
            if (message.length() > 0) {
                message.append("\n\n");
            }
            message.append("伏魔塔（801，通关第 11 层结算）\n")
                    .append("· 连续空手 ").append(fumo.misses())
                    .append(" / ").append(fumo.pity())
                    .append("  →  ").append(fumo.pity() - 1)
                    .append("\n  推到 ").append(fumo.pity() - 1)
                    .append(" 后，下一把通关必掉一件伏魔套或首饰（")
                    .append(fumo.claimed().size()).append(" / 10 件已领）");
        } else if (fumo.ready()) {
            message.append("\n\n伏魔塔（801）已经处于「下一把必掉」状态，不重复处理。");
        }
        if (bawangFillable) {
            if (message.length() > 0) {
                message.append("\n\n");
            }
            message.append("霸王卷轴（虚空炼狱 · 霸王虎）\n");
            if (bawang.present()) {
                message.append("· 累计通关 ").append(bawang.n())
                        .append(" 次，本轮已经连续 ").append(bawang.sinceLast())
                        .append(" / ").append(SaveService.BAWANG_PITY).append(" 次没出\n");
            } else {
                message.append("· 这个角色还没打过虚空炼狱，存档里没有计数\n");
            }
            message.append("  → 累计推到 ").append(bawang.target())
                    .append("\n  规则：连续 ").append(SaveService.BAWANG_PITY)
                    .append(" 次没出必出一卷，且每个角色只能出一次（你已经拿到过就别推了，游戏不会给第二张）。");
        } else if (bawangOwned) {
            message.append("\n\n霸王卷轴（虚空炼狱）这个角色已经拿到过（灵兽栏有霸王虎，"
                            + "或背包里还有一张卷轴），每个角色只能出一次，不再处理。");
        } else if (bawang.present()) {
            message.append("\n\n霸王卷轴（虚空炼狱）已经连续 ")
                    .append(bawang.sinceLast())
                    .append(" 次没出、到达保底线，下一趟通关必出。");
        }
        if (!confirm("BOSS 掉落保底", message.toString())) {
            return;
        }

        try {
            int changed = service.fillAllPity(currentSlot);
            SaveOutcome outcome = persist();
            String fumoNote = (fumoFillable ? "，含伏魔塔 1 项" : "")
                    + (bawangFillable ? "，含霸王卷轴 1 项" : "");
            logOk("%s：%d 项保底已推到必掉（其中 %d 项新建计数%s），代数 %d%s".formatted(
                    service.character(currentSlot).name(), changed, added, fumoNote,
                    outcome.generation(), verifiedNote(outcome)));
            refreshCharBlock();
            updateStatus();
            log("下一次击杀对应 BOSS / 通关伏魔塔 / 通关虚空炼狱必掉；出货后游戏会把计数清零，不会留下痕迹");
        } catch (SaveFormatException e) {
            showError("推满失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    /**
     * 确认弹窗：排版与 {@link #showMessage} 一致，带「确定 / 取消」。
     *
     * @param title   标题
     * @param message 内容
     * @return 点了确定返回 true
     */
    private boolean confirm(String title, String message) {
        String text = message == null ? "" : message;
        int lines = Math.max(4, Math.min(20, text.split("\n", -1).length + text.length() / 60));
        JTextArea area = new JTextArea(text, lines, 48);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBackground(Theme.SURFACE);
        area.setForeground(Theme.TEXT);
        area.setFont(Theme.FONT);
        area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(540,
                Math.min(area.getPreferredSize().height + 28, 440)));
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        Window owner = SwingUtilities.getWindowAncestor(this);
        int choice = JOptionPane.showConfirmDialog(owner, scroll, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        return choice == JOptionPane.OK_OPTION;
    }

    /**
     * 补满今日副本额度：把今天副本还没刷满的那部分一次记进掉落账本。
     *
     * <p>这是通宝唯一的修改入口，走的是游戏自己的记账渠道 —— 金额记在当天、
     * 不超过每日上限、是 5 的倍数，余额与账本同时加同一个数，所以读档、签到、
     * 商城购买都不受影响。
     */
    private void claimTodayDrop() {
        if (guardGameRunning() || service == null) {
            return;
        }
        try {
            long added = service.claimTodayDrop();
            SaveOutcome outcome = persist();
            logOk("今日副本额度 +%d，通宝 → %d，代数 %d%s".formatted(
                    added, service.currencies().tongbao(),
                    outcome.generation(), verifiedNote(outcome)));
            refreshCurrencies();
            updateStatus();
            log("已记入今天的副本掉落明细；下次启动游戏登录后生效");
        } catch (SaveFormatException e) {
            showError("补满失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    /**
     * 把本周渊晶进度补满到每周上限。
     */
    private void fillAbyssWeekly() {
        if (guardGameRunning() || service == null) {
            return;
        }
        try {
            long added = service.fillAbyssWeekly();
            if (added == 0) {
                showInfo("本周已满", "本周渊晶额度已经拉满，无需再补。");
                return;
            }
            SaveOutcome outcome = persist();
            logOk("本周渊晶 +%d，余额 → %d，代数 %d%s".formatted(
                    added, service.abyssWeekly().crystal(),
                    outcome.generation(), verifiedNote(outcome)));
            refreshCurrencies();
            updateStatus();
            log("下次启动游戏登录后生效");
        } catch (SaveFormatException e) {
            showError("拉满失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    /**
     * 保存魔点。
     */
    private void saveDemon() {
        if (guardGameRunning() || service == null) {
            return;
        }
        long value = parseLong(demonField.getText());
        if (value < 0) {
            showError("数值不对", "请输入 0 或正整数");
            return;
        }
        try {
            service.setDemonPoints(value);
            SaveOutcome outcome = persist();
            logOk("魔点 → %d，代数 %d%s"
                    .formatted(value, outcome.generation(), verifiedNote(outcome)));
            refreshCurrencies();
            updateStatus();
            log("下次启动游戏登录后生效");
        } catch (SaveFormatException e) {
            showError("改魔点失败", e.getMessage());
        } catch (IOException e) {
            showError("写入失败", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 公共流程

    /**
     * 统一落盘：重算签名 + 备份 + 原子替换 + 回读自检。
     *
     * <p>自检不过就直接抛异常。此时磁盘上已经是坏档了，但原始备份还在，
     * 提示里会带上备份路径，用户可以手动还原——这比"静默成功"安全得多。
     *
     * @return 保存结果
     * @throws IOException         读写失败
     * @throws SaveFormatException 签名自检未通过
     */
    private SaveOutcome persist() throws IOException, SaveFormatException {
        SaveOutcome outcome = service.save();
        if (!outcome.verified()) {
            throw new SaveFormatException("写入后签名自检未通过，原档备份在 " + outcome.backup());
        }
        return outcome;
    }

    /**
     * 写盘之后重新读一遍存档，并尽量停留在原来的角色/物品上。
     *
     * @param slot 要保持选中的卡槽
     * @param keep 要保持选中的物品（可为 null）
     */
    private void reloadAfterWrite(int slot, ItemView keep) {
        if (service == null) {
            return;
        }
        openSave(service.path(), slot, keep);
    }

    /**
     * 在新加载的数据里重新找到之前选中的物品并选中。
     *
     * <p>物品可能刚被加进背包（原列表里没有），所以找不到时会重读一次背包再试。
     *
     * @param keep 目标物品
     */
    private void reselect(ItemView keep) {
        if (keep == null) {
            return;
        }
        for (ItemView item : currentItems) {
            if (isSame(item, keep)) {
                selectItem(item);
                return;
            }
        }
        if (currentSlot >= 0) {
            CharacterView character = service.character(currentSlot);
            if (character != null) {
                slotItems = new ArrayList<>(character.items());
                slotMaterials = new ArrayList<>(character.materials());
                slotEquipment = new ArrayList<>(character.equipment());
                // 刚加的东西可能落在别的分区：跟着跳过去，否则用户会以为没加成功
                if (keep.section() != null && !keep.section().equals(activeSection)) {
                    activeSection = keep.section();
                    sectionTabs.select(activeSection);
                }
                sectionTabs.setCount(ItemCatalog.SECTION_ITEMS, slotItems.size());
                sectionTabs.setCount(ItemCatalog.SECTION_MATERIALS, slotMaterials.size());
                sectionTabs.setCount(ItemCatalog.SECTION_EQUIPMENT, slotEquipment.size());
                applySection();
                for (ItemView item : currentItems) {
                    if (isSame(item, keep)) {
                        selectItem(item);
                        return;
                    }
                }
            }
        }
    }

    /**
     * 探测游戏进程，状态变化时才更新界面（避免每 2 秒重刷一遍按钮）。
     */
    private void checkGameState() {
        boolean running = GameWatcher.isGameRunning();
        if (running == gameRunning) {
            return;
        }
        gameRunning = running;
        applyLock();
        updateStatus();
        if (running) {
            logError("检测到游戏正在运行，修改功能已锁定");
        } else {
            logOk("游戏已退出，修改功能已解锁");
        }
    }

    /**
     * 按当前游戏状态统一上锁/解锁所有编辑入口。
     */
    private void applyLock() {
        boolean editable = !gameRunning;
        bannerLabel.setVisible(gameRunning);
        bannerLabel.setText(gameRunning
                ? "游戏正在运行：修改功能已锁定，请先退出游戏（浏览与刷新仍可用）"
                : "");
        setLockable(demonField, editable);
        setLockable(demonSave, editable, Theme.ACCENT);
        // 角色加成块的可用性还取决于当前角色/字段是否齐全，交给它自己算
        refreshCharBlock();
        // 「补满额度」还取决于今天有没有掉落记录 / 额度是否用完，交给它自己算
        updateDropClaim();
        updateAbyssClaim();
        setLockable(itemSave, editable, Theme.ACCENT);
        setLockable(restoreButton, editable);
        // 「添加」按钮的状态由「游戏是否运行 + 当前页签」共同决定，统一交给它算
        updateAddButton();
        // 浏览、刷新、切换存档都是只读操作：最多重读磁盘上的 save.dat，
        // 一个字节都不会写回去，因此不受游戏运行状态影响。
        // 之前一并锁死，导致游戏一开连"看一眼当前数据"都做不到，属于误伤。
        setLockable(browseButton, true);
        setLockable(refreshButton, true);
        saveCombo.setEnabled(true);
        cards.values().forEach(card -> card.setEditingEnabled(editable));
        if (selectedItem != null) {
            itemCountField.setEnabled(editable);
        }
    }

    /**
     * 单纯启用/禁用（输入框这类没有"压暗"问题的控件）。
     *
     * @param component 控件
     * @param enabled   是否启用
     */
    private static void setLockable(JComponent component, boolean enabled) {
        component.setEnabled(enabled);
    }

    /**
     * 启用/禁用按钮，同时手动调色。
     *
     * <p>Metal 外观下禁用按钮只会把文字变灰、底色仍然鲜亮，看起来"还能点"，
     * 所以这里额外压暗背景，让"已锁定"这件事一眼可见。
     *
     * @param button       按钮
     * @param enabled      是否启用
     * @param enabledColor 启用时的底色
     */
    private static void setLockable(javax.swing.AbstractButton button, boolean enabled, Color enabledColor) {
        button.setEnabled(enabled);
        button.setBackground(enabled ? enabledColor : new Color(0x1A, 0x2A, 0x46));
        button.setForeground(enabled ? Color.WHITE : Theme.MUTED);
    }

    /**
     * 写操作前的统一守卫：游戏在跑就弹窗拒绝。
     *
     * @return 需要中止操作时返回 true
     */
    private boolean guardGameRunning() {
        if (gameRunning) {
            logError("已拦截：游戏正在运行，请先退出 XiewaPai.exe 再修改");
            showInfo("游戏正在运行", "请先退出游戏再修改存档，否则游戏会把改动覆盖掉。");
            return true;
        }
        return false;
    }

    /**
     * 切换忙碌光标（读档可能涉及 DPAPI 与几百 KB 解密，给个反馈）。
     *
     * @param busy 是否忙碌
     */
    private void setBusy(boolean busy) {
        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    // ---------------------------------------------------------------- 日志与提示

    /**
     * 追加一行普通日志（正文色）。
     *
     * @param message 文本
     */
    private void log(String message) {
        appendLog(message, Theme.TEXT);
    }

    /**
     * 追加一行成功日志（绿色）。
     *
     * @param message 文本
     */
    private void logOk(String message) {
        appendLog(message, Theme.OK);
    }

    /**
     * 追加一行提示日志（灰色）。
     *
     * @param message 文本
     */
    private void logWarn(String message) {
        appendLog(message, Theme.MUTED);
    }

    /**
     * 追加一行错误日志（红色）。
     *
     * @param message 文本
     */
    private void logError(String message) {
        appendLog(message, Theme.DANGER);
    }

    /**
     * 真正往日志面板写字：带时间戳、按级别上色，并裁掉过老的内容。
     *
     * @param message 文本
     * @param color   颜色
     */
    private void appendLog(String message, Color color) {
        StyledDocument document = logPane.getStyledDocument();
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, color);
        StyleConstants.setFontFamily(attributes, "Microsoft YaHei UI");
        StyleConstants.setFontSize(attributes, 12);
        String line = "[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] "
                + message + "\n";
        try {
            document.insertString(document.getLength(), line, attributes);
            if (document.getLength() > LOG_LIMIT) {
                document.remove(0, document.getLength() - LOG_LIMIT);
            }
        } catch (BadLocationException e) {
            // 理论上不会发生；真发生了也不能因为日志把操作搞挂，直接吞掉
        }
        logPane.setCaretPosition(document.getLength());
    }

    /**
     * 弹一个错误框。
     *
     * @param title   标题
     * @param message 内容
     */
    private void showError(String title, String message) {
        showMessage(title, message, JOptionPane.ERROR_MESSAGE);
    }

    /**
     * 弹一个信息框。
     *
     * @param title   标题
     * @param message 内容
     */
    private void showInfo(String title, String message) {
        showMessage(title, message, JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * 弹框本体。
     *
     * <p>用 {@link JTextArea} 而不是默认的短标签：错误信息经常是多行（含两条密钥来源的失败原因），
     * 默认 {@code JOptionPane} 会把长文本拉成一条横贯屏幕的直线，很难读。
     *
     * @param title   标题
     * @param message 内容
     * @param type    消息类型
     */
    private void showMessage(String title, String message, int type) {
        String text = message == null ? "" : message;
        int lines = Math.max(3, Math.min(14, text.split("\n", -1).length + text.length() / 46));
        JTextArea area = new JTextArea(text, lines, 46);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBackground(Theme.SURFACE);
        area.setForeground(Theme.TEXT);
        area.setFont(Theme.FONT);
        area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(520, Math.min(area.getPreferredSize().height + 28, 420)));
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        Window owner = SwingUtilities.getWindowAncestor(this);
        JOptionPane.showMessageDialog(owner, scroll, title, type);
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 判断两件物品是不是同一个背包位置上的同一样东西。
     *
     * <p>只比 ID 不够：同一种道具可能同时出现在「道具」和「材料」两个分区里。
     *
     * @param a 物品 A
     * @param b 物品 B
     * @return 相同返回 true
     */
    private static boolean isSame(ItemView a, ItemView b) {
        if (a == null || b == null || a.id() != b.id() || !a.section().equals(b.section())) {
            return false;
        }
        // 装备一条就是一件，同 ID 可以有很多件：穿戴件按部位区分，背包件按数组下标区分，
        // 不能像道具/材料那样只比 ID——否则点一件会亮一排
        if (ItemCatalog.SECTION_EQUIPMENT.equals(a.section())) {
            if (a.worn() || b.worn()) {
                return java.util.Objects.equals(a.part(), b.part());
            }
            return a.index() == b.index();
        }
        return true;
    }

    /**
     * 宽松解析整数（容忍千分位逗号与首尾空格）。
     *
     * @param text 文本
     * @return 解析结果；失败返回 -1
     */
    private static long parseLong(String text) {
        try {
            return Long.parseLong(text == null ? "" : text.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 把多行文本压成一行（日志区一行一条，不压会撑得很乱）。
     *
     * @param text 文本
     * @return 单行文本
     */
    private static String oneLine(String text) {
        return text == null ? "" : text.replace("\n", " ");
    }

    /**
     * 自检失败时给日志补一句后缀。
     *
     * @param outcome 保存结果
     * @return 后缀文本
     */
    private static String verifiedNote(SaveOutcome outcome) {
        // v1.0.9.6 起走「代数前进」的主档快速通道：游戏会直接采用这份存档并更新水位账本，
        // 不弹「存档恢复」提示，也不消耗 24 小时冷却。
        return outcome.verified() ? "" : "（签名异常）";
    }

    /**
     * 关闭窗口时释放图标句柄（ZipFile 不关会一直占着文件）。
     *
     * @param event 窗口事件
     */
    @Override
    protected void processWindowEvent(java.awt.event.WindowEvent event) {
        super.processWindowEvent(event);
        if (event.getID() == java.awt.event.WindowEvent.WINDOW_CLOSING && icons != null) {
            icons.close();
        }
    }

    /**
     * 主入口（供打包后的 exe 调用）。
     *
     * <p>顺手装一个全局异常兜底：Swing 的事件处理里抛异常时，
     * 默认行为只是往控制台打一行栈——而打包后的 exe 根本没有控制台，
     * 用户只会看到"点了没反应"。这里改成弹窗 + 写日志。
     */
    public static void launch() {
        Theme.install();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                        "程序出现未预期的错误：\n\n" + error + "\n\n"
                                + "如果修改已经写入，原档备份在存档目录的 backup 文件夹里。",
                        "出错了", JOptionPane.ERROR_MESSAGE)));
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            startKeyHarvest(frame);
        });
    }

    /**
     * 后台把本机所有游戏账号的密钥收进密钥库。
     *
     * <p>这是「拷到别的电脑也能用」的自动化来源：在装有游戏的机器上跑过一次之后，
     * 密钥就落在程序目录的 {@code keyvault.json} 里，整个文件夹搬走时一并带走。
     * 放后台线程是因为 DPAPI 解密钥要读 exe、调系统接口，几十毫秒到几百毫秒，
     * 放主线程会让窗口先白一下。
     *
     * @param frame 用于回写日志的主窗口
     */
    private static void startKeyHarvest(MainFrame frame) {
        Thread worker = new Thread(() -> {
            int stored = SaveKeys.harvest();
            if (stored > 0) {
                SwingUtilities.invokeLater(() -> frame.log(
                        "已收录 %d 个账号的密钥到密钥库，之后整个文件夹拷到别的电脑也能直接打开存档"
                                .formatted(stored)));
            }
        }, "key-vault-harvest");
        worker.setDaemon(true);
        worker.start();
    }
}
