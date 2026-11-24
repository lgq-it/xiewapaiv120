package com.chengyi.xiewa.save.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * 背包分区页签（道具 / 材料）。
 *
 * <p>为什么不用 {@link javax.swing.JTabbedPane}：Metal 外观的下划线页签在深色底上像一张灰贴纸，
 * 和整套深海军蓝配色打架，还得逐个覆盖 UI 属性。这里直接自绘一排圆角胶囊，
 * 选中项填强调色、未选中透明，右侧挂一个分区件数，一眼能看出「哪一页有多少东西」。
 *
 * <p>分组键沿用存档里的分区名（{@code items} / {@code materials}），
 * 与游戏背包的分类口径一致，不另造一套映射。装备分区不提供页签：
 * 它在存档里没有数量概念，游戏端还有解锁校验，本工具只改能改的东西。
 */
public final class SectionTabs extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 胶囊高度。 */
    private static final int TAB_HEIGHT = 30;

    /** 单个页签的左右内边距。 */
    private static final int TAB_PAD = 12;

    /** 页签之间的间距。 */
    private static final int TAB_GAP = 6;

    /** 名称与件数之间的间距。 */
    private static final int COUNT_GAP = 6;

    /**
     * 一个页签定义。
     *
     * @param key   分区键（{@code items} / {@code materials}）
     * @param label 显示名（道具 / 材料）
     */
    public record Tab(String key, String label) {
    }

    private final List<TabView> views = new ArrayList<>();

    /** key → 页签视图，回填件数时用。 */
    private final Map<String, TabView> byKey = new LinkedHashMap<>();

    private String selected;

    private Consumer<String> onChange = key -> {
    };

    /**
     * @param tabs 页签定义（按显示顺序）
     */
    public SectionTabs(Tab... tabs) {
        setOpaque(false);
        setLayout(null); // 手动摆放：宽度按文字算，不想被布局管理器拉伸
        for (Tab tab : tabs) {
            TabView view = new TabView(tab.key(), tab.label());
            views.add(view);
            byKey.put(tab.key(), view);
            add(view);
            if (selected == null) {
                selected = tab.key();
            }
        }
    }

    /**
     * 当前选中的分区键。
     *
     * @return 分区键
     */
    public String selected() {
        return selected;
    }

    /**
     * 切换选中页签（不触发回调，用于程序内部同步）。
     *
     * @param key 分区键
     */
    public void select(String key) {
        if (key == null || !byKey.containsKey(key)) {
            return;
        }
        selected = key;
        views.forEach(view -> view.repaint());
    }

    /**
     * 注册切换回调。
     *
     * @param listener 收到新的分区键
     */
    public void onChange(Consumer<String> listener) {
        this.onChange = listener == null ? key -> {
        } : listener;
    }

    /**
     * 回填某分区的件数（-1 表示不显示数字）。
     *
     * @param key   分区键
     * @param count 件数
     */
    public void setCount(String key, int count) {
        TabView view = byKey.get(key);
        if (view != null && view.count != count) {
            view.count = count;
            view.revalidate();
            revalidate();
            repaint();
        }
    }

    /** 按页签顺序横向排布（各自首选宽度）。 */
    @Override
    public void doLayout() {
        int x = 0;
        for (TabView view : views) {
            Dimension size = view.getPreferredSize();
            view.setBounds(x, 0, size.width, TAB_HEIGHT);
            x += size.width + TAB_GAP;
        }
    }

    /** 高度固定，宽度取所有页签之和。 */
    @Override
    public Dimension getPreferredSize() {
        int width = 0;
        for (TabView view : views) {
            width += view.getPreferredSize().width + TAB_GAP;
        }
        return new Dimension(Math.max(width - TAB_GAP, 0), TAB_HEIGHT);
    }

    /**
     * 单个胶囊页签。
     */
    private final class TabView extends JComponent {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final String label;
        private int count = -1;
        private boolean hover;

        TabView(String key, String label) {
            this.key = key;
            this.label = label;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                /**
                 * 用 mousePressed 而不是 mouseClicked。
                 *
                 * <p>Windows 上 AWT 只有在「按下与释放之间完全没有移动」时才合成 MOUSE_CLICKED，
                 * 而高 DPI + 高回报率鼠标按下时的轻微抖动就会被判成拖拽，导致单击丢失——
                 * 表现就是"页签要点好几下才切得过去"。按下即响应则不受抖动影响。
                 */
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && !key.equals(selected)) {
                        select(key);
                        onChange.accept(key);
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(Theme.FONT);
            int width = TAB_PAD * 2 + metrics.stringWidth(label);
            if (count >= 0) {
                width += COUNT_GAP + getFontMetrics(Theme.FONT_SMALL).stringWidth(Integer.toString(count));
            }
            return new Dimension(width, TAB_HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean active = key.equals(selected);
            if (active) {
                g.setColor(Theme.ACCENT);
            } else if (hover) {
                g.setColor(Theme.CARD_HOVER);
            } else {
                g.setColor(new Color(0x0E, 0x22, 0x42));
            }
            g.fillRoundRect(0, 0, getWidth(), getHeight(), TAB_HEIGHT, TAB_HEIGHT);
            if (!active) {
                g.setColor(Theme.LINE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, TAB_HEIGHT, TAB_HEIGHT);
            }

            g.setFont(Theme.FONT);
            FontMetrics metrics = g.getFontMetrics();
            int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            int x = TAB_PAD;
            g.setColor(active ? Color.WHITE : Theme.TEXT);
            g.drawString(label, x, baseline);
            if (count >= 0) {
                x += metrics.stringWidth(label) + COUNT_GAP;
                g.setFont(Theme.FONT_SMALL);
                FontMetrics small = g.getFontMetrics();
                int smallBaseline = (getHeight() - small.getHeight()) / 2 + small.getAscent();
                g.setColor(active ? new Color(0xD8, 0xE6, 0xFF) : Theme.MUTED);
                g.drawString(Integer.toString(count), x, smallBaseline);
            }
            g.dispose();
        }
    }
}
