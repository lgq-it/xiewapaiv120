package com.chengyi.xiewa.save.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.ImageIcon;
import javax.swing.JPanel;

import com.chengyi.xiewa.save.model.ItemView;

/**
 * 背包里的一个格子：图标 + 名称 + 数量。
 *
 * <p>整体自绘（不用子控件），这样点击命中区就是整个格子，且能自由处理选中/悬停态。
 */
public final class ItemCell extends JPanel {

    /** 单元格宽度。 */
    public static final int CELL_WIDTH = 86;

    /** 单元格高度。 */
    public static final int CELL_HEIGHT = 96;

    /** 名称最多显示的字数，超出用省略号（完整名称在悬浮提示里）。 */
    private static final int NAME_LIMIT = 6;

    private final ItemView item;

    /**
     * 图标的延迟来源。
     *
     * <p>不在构造时就把图取出来：材料页有三百多件，一次性解码三百多张 PNG 会把界面线程堵住
     * 一两秒，这期间点什么都没反应。改成画到哪个格子才取哪张图（视口内只会有几十个），
     * 页签切换立刻就能响应。
     */
    private final Supplier<ImageIcon> iconSource;

    /** 已取到的图标；{@code null} 表示还没取。 */
    private ImageIcon icon;

    private final Consumer<ItemView> onClick;

    private boolean selected;
    private boolean hovered;

    /**
     * @param item       物品
     * @param iconSource 图标来源（首次绘制时才调用）
     * @param onClick    点击回调
     */
    public ItemCell(ItemView item, Supplier<ImageIcon> iconSource, Consumer<ItemView> onClick) {
        this.item = item;
        this.iconSource = iconSource;
        this.onClick = onClick;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Dimension size = new Dimension(CELL_WIDTH, CELL_HEIGHT);
        setPreferredSize(size);
        setMinimumSize(size);
        String position = item.worn()
                ? "身上穿戴 · " + item.part() + " 部位"
                : "%s 区第 %d 项".formatted(sectionLabel(), item.index());
        setToolTipText(("<html><b>%s</b><br>ID %d · %s<br>"
                + "<span style='color:#8FA3BF'>位置 %s</span></html>")
                .formatted(item.name(), item.id(), countLabel(), position));
        addMouseListener(new MouseAdapter() {
            /**
             * 按下即选中，不用 mouseClicked：Windows 上按下与释放之间只要有极小的光标抖动
             * （高 DPI + 高回报率鼠标很常见），AWT 就不再合成 MOUSE_CLICKED，
             * 表现为"格子要点好几下才选得上"。
             */
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    onClick.accept(ItemCell.this.item);
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                repaint();
            }
        });
    }

    /**
     * 对应的数据对象。
     *
     * @return 物品
     */
    public ItemView item() {
        return item;
    }

    /**
     * 设置选中态。
     *
     * @param selected 是否选中
     */
    public void setSelected(boolean selected) {
        if (this.selected != selected) {
            this.selected = selected;
            repaint();
        }
    }

    /**
     * 自绘整个格子：圆角底板 → 图标 → 名称 → 数量。
     *
     * <p>不用 {@link javax.swing.JButton} + {@link javax.swing.ImageIcon} 的组合，
     * 是因为按钮自带内边距和焦点框，尺寸很难和「图标 44 + 两行文字」精确对齐；
     * 自绘反而更好控制，而且悬停/选中态能自由配色。
     *
     * @param graphics 画笔
     */
    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 底板：选中 > 悬停 > 常态，三档颜色区分
        Color background = selected ? new Color(0x1C, 0x3C, 0x6E)
                : hovered ? Theme.CARD_HOVER : Theme.CARD;
        g.setColor(background);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g.setColor(selected ? Theme.ACCENT : Theme.LINE);
        g.setStroke(new BasicStroke(selected ? 2f : 1f));
        g.drawRoundRect(0, 0, getWidth() - 2, getHeight() - 2, 14, 14);

        // 图标居中、名称居中、数量居中：三行都按实际文字宽度算 x，才能真居中
        if (icon == null) {
            icon = iconSource.get();
        }
        int iconX = (getWidth() - IconRepository.ICON_SIZE) / 2;
        g.drawImage(icon.getImage(), iconX, 10, null);

        g.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        g.setColor(selected ? Color.WHITE : Theme.TEXT);
        String name = abbrev(item.name());
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(name, (getWidth() - metrics.stringWidth(name)) / 2, 70);

        g.setFont(new Font("Consolas", Font.BOLD, 12));
        g.setColor(Theme.GOLD);
        String count = bottomLabel();
        metrics = g.getFontMetrics();
        g.drawString(count, (getWidth() - metrics.stringWidth(count)) / 2, 86);
        g.dispose();
    }

    /**
     * 格子底部的标签：装备显示强化等级（未强化显示 ×1），其余显示数量。
     *
     * @return 标签文本
     */
    private String bottomLabel() {
        if ("equipment".equals(item.section())) {
            return item.strengthen() > 0 ? "强化+" + item.strengthen() : "×1";
        }
        return "×" + compact(item.count());
    }

    /**
     * 分区的中文名。
     *
     * @return 道具 / 材料 / 装备
     */
    private String sectionLabel() {
        return switch (item.section()) {
            case "materials" -> "材料";
            case "equipment" -> item.worn() ? "穿戴 · " + item.part() : "装备";
            default -> "道具";
        };
    }

    /**
     * 悬浮提示里的数量/强化行。
     *
     * @return 提示文本
     */
    private String countLabel() {
        if ("equipment".equals(item.section())) {
            return "强化 +" + item.strengthen();
        }
        return "数量 " + item.count();
    }

    /**
     * 名称截断：格子只有 86 像素宽，超出长度直接截断加省略号，
     * 完整名称放在悬浮提示里（悬停即见，不必挤在格子里）。
     *
     * @param name 完整名称
     * @return 截断后的名称
     */
    private static String abbrev(String name) {
        if (name == null) {
            return "";
        }
        return name.length() <= NAME_LIMIT ? name : name.substring(0, NAME_LIMIT) + "…";
    }

    /**
     * 大数字压缩显示（12000 → 1.2万），避免撑破格子。
     *
     * @param value 数量
     * @return 简短文本
     */
    public static String compact(long value) {
        if (value < 10_000) {
            return Long.toString(value);
        }
        if (value < 100_000_000L) {
            double wan = value / 10_000.0;
            return (wan >= 100 ? String.format("%.0f", wan) : String.format("%.1f", wan)) + "万";
        }
        double yi = value / 100_000_000.0;
        return (yi >= 100 ? String.format("%.0f", yi) : String.format("%.2f", yi)) + "亿";
    }
}
