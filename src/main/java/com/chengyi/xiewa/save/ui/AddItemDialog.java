package com.chengyi.xiewa.save.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.chengyi.xiewa.save.model.ItemCatalog;

/**
 * 「添加物品」弹窗：搜索 + 勾选多选 + 统一数量。
 *
 * <p>交互刻意做成「勾选」而不是「按住 Ctrl 多选」，原因有两个：
 * <ul>
 *   <li>一次要加的往往是同一类东西（材料、强化石…），勾选更顺手；</li>
 *   <li>鼠标拖选容易误碰，勾选点一下就是一下，状态一眼可见。</li>
 * </ul>
 *
 * <p>候选集含道具、材料与当前版本可获得的装备。装备按游戏同款的
 * {@code {uid, id}} 条目追加进背包（不带商城标志，对账本隐形），每件一条、
 * 不与已拥有的合并；道具与材料仍然只列未拥有的。
 */
public final class AddItemDialog extends JDialog {

    /** 列表一次最多渲染多少行（渲染上万行会明显卡顿，靠搜索缩小范围即可）。 */
    private static final int DISPLAY_LIMIT = 150;

    /** 一次最多能勾选多少件（与命令行版的批次上限保持一致）。 */
    public static final int MAX_PICK = 200;

    /** 每件默认添加的数量。 */
    private static final String DEFAULT_COUNT = "99";

    /**
     * 选择结果。
     *
     * @param items      勾选中的物品（按勾选顺序）
     * @param count      每件要添加的数量（装备固定为 1）
     * @param strengthen 装备的初始强化等级（道具/材料为 0）
     */
    public record Result(List<ItemCatalog.Entry> items, long count, int strengthen) {
    }

    /** 全部候选（道具/材料已排除当前角色已有的物品）。 */
    private final transient List<ItemCatalog.Entry> all;

    /** 物品目录（查装备品质上限用）。 */
    private final transient ItemCatalog catalog;

    /** 当前列表实际展示的条目（受搜索关键字与 {@link #DISPLAY_LIMIT} 限制）。 */
    private final DefaultListModel<ItemCatalog.Entry> model = new DefaultListModel<>();

    /** 勾选结果：key 为物品 ID，便于去重与判断。跨页签保留，切回去还勾着。 */
    private final transient Map<Integer, ItemCatalog.Entry> picked = new LinkedHashMap<>();

    /** 与主界面同款的分区页签：道具 / 材料 / 装备。 */
    private final SectionTabs sectionTabs = new SectionTabs(
            new SectionTabs.Tab(ItemCatalog.SECTION_ITEMS, "道具"),
            new SectionTabs.Tab(ItemCatalog.SECTION_MATERIALS, "材料"),
            new SectionTabs.Tab(ItemCatalog.SECTION_EQUIPMENT, "装备"));

    private final JList<ItemCatalog.Entry> list = new JList<>(model);
    private final JTextField search = Theme.searchField(24, "搜索名称或 ID");
    private final JLabel summary = Theme.label("", Theme.MUTED, Theme.FONT_SMALL);
    private final JTextField countField = Theme.numberField(8);

    /** 装备页签专用：初始强化等级输入。 */
    private final JTextField strengthenField = Theme.numberField(8);

    /** 装备页签的品质上限提示（随勾选内容动态更新）。 */
    private final JLabel strengthenHint = Theme.label("", Theme.MUTED, Theme.FONT_SMALL);

    /** 数量行控件（道具/材料页签显示，装备页签隐藏）。 */
    private final JComponent[] countControls = new JComponent[3];

    /** 强化行控件（装备页签显示，其余页签隐藏）。 */
    private final JComponent[] strengthenControls = new JComponent[3];

    /** 当前页签在「全部候选」里匹配到的件数（可能超过 {@link #DISPLAY_LIMIT}）。 */
    private int matched;

    private Result result;

    private AddItemDialog(Window owner, List<ItemCatalog.Entry> candidates, String defaultSection,
                          ItemCatalog catalog) {
        super(owner, "添加物品到背包", ModalityType.APPLICATION_MODAL);
        this.all = candidates;
        this.catalog = catalog;
        setLayout(new BorderLayout());
        getContentPane().setBackground(Theme.SURFACE);

        if (defaultSection != null) {
            sectionTabs.select(defaultSection);
        }
        sectionTabs.onChange(key -> {
            updateFooterMode();
            refill(search.getText());
        });

        add(buildHeader(), BorderLayout.NORTH);
        add(buildList(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        countField.setText(DEFAULT_COUNT);
        strengthenField.setText("0");
        updateFooterMode();
        refill("");
        setSize(new Dimension(680, 620));
        setLocationRelativeTo(owner);
    }

    /**
     * 以模态方式弹出。
     *
     * @param owner          父窗口
     * @param candidates     候选物品（已排除角色已有物品，通常也已按分区过滤）
     * @param defaultSection 打开时落在哪个页签（跟主界面当前页签一致）
     * @param catalog        物品目录（装备页签查品质上限）
     * @return 用户确认的结果；取消返回 {@code null}
     */
    public static Result open(Window owner, List<ItemCatalog.Entry> candidates, String defaultSection,
                              ItemCatalog catalog) {
        AddItemDialog dialog = new AddItemDialog(owner, candidates, defaultSection, catalog);
        dialog.setVisible(true);
        return dialog.result;
    }

    /**
     * 顶部：搜索框 + 「全选当前列表」/「清空勾选」。
     *
     * @return 顶部面板
     */
    private JComponent buildHeader() {
        JPanel header = Theme.padded(Theme.SURFACE, 14, 16, 8, 16);
        header.setLayout(new BorderLayout(10, 8));

        // 先分页签再搜索：和主界面、游戏背包都是同一个心智模型
        JPanel tabsRow = Theme.panel(Theme.SURFACE);
        tabsRow.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabsRow.add(sectionTabs);
        header.add(tabsRow, BorderLayout.NORTH);

        JPanel searchRow = Theme.panel(Theme.SURFACE);
        searchRow.setLayout(new BorderLayout(10, 0));
        searchRow.add(Theme.label("搜索", Theme.MUTED, Theme.FONT_SMALL), BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);

        JPanel buttons = Theme.panel(Theme.SURFACE);
        buttons.setLayout(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton selectAll = Theme.ghost("全选当前列表");
        selectAll.addActionListener(e -> pickAllVisible());
        JButton clear = Theme.ghost("清空勾选");
        clear.addActionListener(e -> clearPicked());
        buttons.add(selectAll);
        buttons.add(clear);
        searchRow.add(buttons, BorderLayout.EAST);
        header.add(searchRow, BorderLayout.CENTER);

        // 输入即过滤：直接重建列表，规模在千级以内，无需防抖
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refill(search.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refill(search.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refill(search.getText());
            }
        });
        return header;
    }

    /**
     * 中部：勾选列表 + 底部统计。
     *
     * @return 中部面板
     */
    private JComponent buildList() {
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.setFixedCellHeight(28);
        list.setBackground(new Color(0x08, 0x18, 0x33));
        list.setForeground(Theme.TEXT);
        list.setFont(Theme.FONT);

        // 按下一行 = 切换这一行的勾选状态（渲染器里画 ☑ / ☐）
        // 用 mousePressed 而非 mouseClicked：后者在 Windows 上会因按下瞬间的光标抖动而丢失
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                int index = indexAt(e);
                if (index >= 0) {
                    toggle(model.get(index));
                }
            }
        });
        // 键盘空格也能切换勾选，方便不用鼠标的场景
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle-pick");
        list.getActionMap().put("toggle-pick", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int index = list.getSelectedIndex();
                if (index >= 0) {
                    toggle(model.get(index));
                }
            }
        });

        JScrollPane scroll = Theme.style(new JScrollPane(list));
        JPanel wrapper = Theme.padded(Theme.SURFACE, 0, 16, 6, 16);
        wrapper.setLayout(new BorderLayout());
        wrapper.add(scroll, BorderLayout.CENTER);
        wrapper.add(summary, BorderLayout.SOUTH);
        summary.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        return wrapper;
    }

    /**
     * 底部：数量/强化输入 + 取消/确认。
     *
     * <p>道具/材料显示「每件添加数量」；装备页签换成「初始强化等级」——
     * 装备一条就是一件，数量没有意义，强化上限随品质走（绿4/蓝8/紫12）。
     *
     * @return 底部面板
     */
    private JComponent buildFooter() {
        JPanel footer = Theme.padded(Theme.SURFACE, 8, 16, 16, 16);
        footer.setLayout(new BorderLayout(10, 0));

        JPanel left = Theme.panel(Theme.SURFACE);
        left.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 0));
        countControls[0] = Theme.label("每件添加数量", Theme.MUTED, Theme.FONT_SMALL);
        countControls[1] = countField;
        countControls[2] = Theme.label("（写进背包的初始数量）", Theme.MUTED, Theme.FONT_SMALL);
        for (JComponent c : countControls) {
            left.add(c);
        }
        strengthenControls[0] = Theme.label("初始强化等级", Theme.MUTED, Theme.FONT_SMALL);
        strengthenControls[1] = strengthenField;
        strengthenControls[2] = strengthenHint;
        for (JComponent c : strengthenControls) {
            left.add(c);
        }
        footer.add(left, BorderLayout.WEST);

        JPanel right = Theme.panel(Theme.SURFACE);
        right.setLayout(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JButton cancel = Theme.ghost("取消");
        cancel.addActionListener(e -> dispose());
        JButton confirm = Theme.primary("添加到背包");
        confirm.addActionListener(e -> confirm());
        right.add(cancel);
        right.add(confirm);
        footer.add(right, BorderLayout.EAST);

        getRootPane().setDefaultButton(confirm);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        return footer;
    }

    /**
     * 按当前页签 + 关键字重建列表（勾选状态保留，因此在页签与关键词之间来回切换不会丢勾选）。
     *
     * @param keyword 关键字，空串表示不过滤
     */
    private void refill(String keyword) {
        String needle = keyword == null ? "" : keyword.trim().toLowerCase();
        String section = sectionTabs.selected();
        model.clear();
        matched = 0;
        for (ItemCatalog.Entry entry : all) {
            // 页签先过一道：道具页只该出现道具，材料页只该出现材料
            if (section != null && !section.equals(entry.section())) {
                continue;
            }
            if (!needle.isEmpty()
                    && !entry.name().toLowerCase().contains(needle)
                    && !Integer.toString(entry.id()).contains(needle)) {
                continue;
            }
            matched++;
            if (model.size() < DISPLAY_LIMIT) {
                model.addElement(entry);
            }
        }
        updateSummary();
        list.repaint();
    }

    /**
     * 刷新底部统计文案（当前页签可选件数 + 跨页签的勾选构成）。
     */
    private void updateSummary() {
        summary.setForeground(Theme.MUTED);
        StringBuilder text = new StringBuilder("「%s」%d 件可选".formatted(label(sectionTabs.selected()), matched));
        if (matched > DISPLAY_LIMIT) {
            text.append("，仅显示前 %d 件（输入关键字可缩小范围）".formatted(DISPLAY_LIMIT));
        } else if (ItemCatalog.SECTION_EQUIPMENT.equals(sectionTabs.selected())) {
            text.append("（装备可与已拥有的重复添加，每勾一件加一件）");
        } else {
            text.append("（已拥有的不在列表里）");
        }
        text.append(" · 已勾选 %d 件".formatted(picked.size()));
        if (!picked.isEmpty()) {
            text.append("（%s）".formatted(pickedBreakdown()));
            String preview = previewPicked();
            if (!preview.isEmpty()) {
                text.append("：").append(preview);
            }
        }
        summary.setText(text.toString());
        updateStrengthenHint();
    }

    /**
     * 勾选构成，例如「道具 2 / 材料 3」：允许跨页签勾选，所以必须让用户看得见勾了哪些类别。
     *
     * @return 构成文案
     */
    private String pickedBreakdown() {
        int items = 0;
        int materials = 0;
        int equipment = 0;
        for (ItemCatalog.Entry entry : picked.values()) {
            switch (entry.section()) {
                case ItemCatalog.SECTION_MATERIALS -> materials++;
                case ItemCatalog.SECTION_EQUIPMENT -> equipment++;
                default -> items++;
            }
        }
        StringBuilder text = new StringBuilder();
        if (items > 0) {
            text.append("道具 ").append(items);
        }
        if (materials > 0) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append("材料 ").append(materials);
        }
        if (equipment > 0) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append("装备 ").append(equipment);
        }
        return text.toString();
    }

    /**
     * 分区键转显示名。
     *
     * @param section 分区键
     * @return 道具 / 材料 / 装备
     */
    private static String label(String section) {
        return switch (section) {
            case ItemCatalog.SECTION_MATERIALS -> "材料";
            case ItemCatalog.SECTION_EQUIPMENT -> "装备";
            default -> "道具";
        };
    }

    /**
     * 已勾选物品的预览（最多 4 个名字，多了用「等」收尾，避免撑破一行）。
     *
     * @return 预览文本
     */
    private String previewPicked() {
        List<String> names = new ArrayList<>();
        for (ItemCatalog.Entry entry : picked.values()) {
            if (names.size() >= 4) {
                names.add("等");
                break;
            }
            names.add(entry.name());
        }
        return String.join("、", names);
    }

    /**
     * 切换一件物品的勾选状态。
     *
     * @param entry 物品
     */
    private void toggle(ItemCatalog.Entry entry) {
        if (picked.remove(entry.id()) == null) {
            if (picked.size() >= MAX_PICK) {
                summary.setForeground(Theme.DANGER);
                summary.setText("一次最多勾选 %d 件，请分批添加".formatted(MAX_PICK));
                return;
            }
            picked.put(entry.id(), entry);
        }
        updateSummary();
        list.repaint();
    }

    /**
     * 勾选当前列表里所有可见条目（超过上限时只勾到上限为止）。
     */
    private void pickAllVisible() {
        for (int i = 0; i < model.size(); i++) {
            ItemCatalog.Entry entry = model.get(i);
            if (picked.containsKey(entry.id())) {
                continue;
            }
            if (picked.size() >= MAX_PICK) {
                summary.setForeground(Theme.DANGER);
                summary.setText("已勾选 %d 件，达到单次上限".formatted(MAX_PICK));
                list.repaint();
                return;
            }
            picked.put(entry.id(), entry);
        }
        updateSummary();
        list.repaint();
    }

    /**
     * 清空全部勾选。
     */
    private void clearPicked() {
        picked.clear();
        updateSummary();
        list.repaint();
    }

    /**
     * 计算鼠标位置对应的行号（点在行外时返回 -1，避免误勾）。
     *
     * @param event 鼠标事件
     * @return 行号或 -1
     */
    private int indexAt(MouseEvent event) {
        int index = list.locationToIndex(event.getPoint());
        if (index < 0 || index >= model.size()) {
            return -1;
        }
        Rectangle bounds = list.getCellBounds(index, index);
        return bounds != null && bounds.contains(event.getPoint()) ? index : -1;
    }

    /**
     * 按当前页签切换底部输入行：道具/材料显示数量，装备显示强化等级。
     */
    private void updateFooterMode() {
        if (countControls[0] == null) {
            return; // 构造早期（buildFooter 未跑）不处理
        }
        boolean equipment = ItemCatalog.SECTION_EQUIPMENT.equals(sectionTabs.selected());
        for (JComponent c : countControls) {
            c.setVisible(!equipment);
        }
        for (JComponent c : strengthenControls) {
            c.setVisible(equipment);
        }
    }

    /**
     * 按勾选内容更新强化上限提示：显示所勾装备的实际上限范围。
     */
    private void updateStrengthenHint() {
        if (!ItemCatalog.SECTION_EQUIPMENT.equals(sectionTabs.selected())) {
            return;
        }
        if (picked.isEmpty()) {
            strengthenHint.setText("（上限随品质：绿+4 / 蓝+8 / 紫+12，首饰与橙色记 0）");
            return;
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        int locked = 0;
        for (ItemCatalog.Entry entry : picked.values()) {
            if (!catalog.canStrengthen(entry.id())) {
                locked++;
                continue;
            }
            int cap = catalog.strengthenCap(entry.id());
            min = Math.min(min, cap);
            max = Math.max(max, cap);
        }
        if (min == Integer.MAX_VALUE) {
            strengthenHint.setText("（勾选的全部不可强化：首饰或橙色装备，将按 +0 添加）");
        } else if (min == max) {
            strengthenHint.setText("（已勾装备强化上限 +" + max
                    + (locked > 0 ? "，另有 " + locked + " 件不可强化记 0" : "") + "）");
        } else {
            strengthenHint.setText("（已勾装备上限 +"
                    + min + " ~ +" + max + "，超出的自动收敛"
                    + (locked > 0 ? "；含 " + locked + " 件不可强化记 0" : "") + "）");
        }
    }

    /**
     * 校验并产出结果。
     */
    private void confirm() {
        if (picked.isEmpty()) {
            summary.setForeground(Theme.DANGER);
            summary.setText("请先勾选至少一件物品（点一下那一行即可勾上）");
            return;
        }
        if (picked.size() > MAX_PICK) {
            summary.setForeground(Theme.DANGER);
            summary.setText("一次最多添加 %d 件，请分批操作".formatted(MAX_PICK));
            return;
        }
        boolean equipment = ItemCatalog.SECTION_EQUIPMENT.equals(sectionTabs.selected());
        long count;
        int strengthen = 0;
        if (equipment) {
            // 装备一条就是一件，数量固定 1；改填初始强化等级
            count = 1;
            try {
                strengthen = Integer.parseInt(strengthenField.getText().trim().replace(",", ""));
            } catch (RuntimeException e) {
                strengthen = -1;
            }
            if (strengthen < 0 || strengthen > ItemCatalog.MAX_STRENGTHEN) {
                summary.setForeground(Theme.DANGER);
                summary.setText("强化等级需在 0 ~ %d 之间".formatted(ItemCatalog.MAX_STRENGTHEN));
                return;
            }
        } else {
            try {
                count = Long.parseLong(countField.getText().trim().replace(",", ""));
            } catch (RuntimeException e) {
                count = 0;
            }
            if (count <= 0) {
                summary.setForeground(Theme.DANGER);
                summary.setText("数量必须大于 0");
                return;
            }
        }
        result = new Result(new ArrayList<>(picked.values()), count, strengthen);
        dispose();
    }

    /**
     * 行渲染：☑ 勾选标记 + 名称 + ID + 分区标签。
     *
     * <p>不用 {@code JCheckBox} 当渲染器，是因为几千行时每个 cell 都带一个真实复选框
     * 会明显拖慢滚动；这里只画字符，够用且轻。
     */
    private final class RowRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, false, false);
            if (value instanceof ItemCatalog.Entry entry) {
                boolean marked = picked.containsKey(entry.id());
                // 分区已由页签表达，行里不再重复标「道具/材料」，把宽度留给名称和 ID
                label.setText("%s %s    #%d"
                        .formatted(marked ? "☑" : "☐", entry.name(), entry.id()));
                label.setForeground(marked ? Theme.GOLD : Theme.TEXT);
            }
            label.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
            label.setFont(Theme.FONT);
            label.setBackground(new Color(0x08, 0x18, 0x33));
            label.setOpaque(true);
            return label;
        }
    }
}
