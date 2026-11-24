package com.chengyi.xiewa.save.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.chengyi.xiewa.save.model.CharacterView;

/**
 * 左侧角色卡槽。
 *
 * <p>点击卡片任意空白处即切换角色；卡片内的功夫豆输入框和保存按钮只处理自己的事，
 * 不触发切换（否则改完数值会顺带把角色也切了）。
 */
public final class CharacterCard extends JPanel {

    /** 卡片高度（所有卡片保持一致，避免列表参差）。 */
    private static final int CARD_HEIGHT = 84;

    /**
     * 交互回调。
     */
    public interface Listener {
        /**
         * 选中某个卡槽。
         *
         * @param slot 卡槽序号
         */
        void onSelect(int slot);

        /**
         * 保存功夫豆。
         *
         * @param slot  卡槽序号
         * @param value 新值
         */
        void onSaveBean(int slot, long value);
    }

    private final int slot;
    private final JTextField beanField;
    private final JButton saveButton;
    private final JLabel nameLabel;
    private final JLabel levelLabel;

    private boolean selected;
    private boolean editingEnabled = true;

    /**
     * @param character 角色快照
     * @param listener  回调
     */
    public CharacterCard(CharacterView character, Listener listener) {
        this.slot = character.slot();
        setOpaque(false);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        nameLabel = Theme.label(character.name().isBlank() ? "（空槽）" : character.name(),
                Theme.TEXT, Theme.FONT_TITLE);
        // 等级与卡槽号一起显示：卡槽号是存档里的数组下标，「改功夫豆」用的就是它，
        // 摆在界面上能省掉用户"这个角色到底是第几个槽"的困惑。
        levelLabel = Theme.label("Lv.%d · 槽 %d".formatted(character.level(), character.slot()),
                Theme.MUTED, Theme.FONT_SMALL);

        JPanel header = Theme.panel(new Color(0, 0, 0, 0));
        header.setOpaque(false);
        header.setLayout(new BorderLayout());
        header.add(nameLabel, BorderLayout.WEST);
        header.add(levelLabel, BorderLayout.EAST);

        JPanel beanRow = Theme.panel(new Color(0, 0, 0, 0));
        beanRow.setOpaque(false);
        beanRow.setLayout(new BorderLayout(8, 0));
        beanRow.add(Theme.label("功夫豆", Theme.MUTED, Theme.FONT_SMALL), BorderLayout.WEST);

        beanField = Theme.numberField(9);
        beanField.setText(Long.toString(character.bean()));
        beanField.setToolTipText("改完点右边的保存");
        beanRow.add(beanField, BorderLayout.CENTER);

        saveButton = Theme.ghost("保存");
        saveButton.addActionListener(e -> {
            if (editingEnabled) {
                listener.onSaveBean(slot, parse(beanField.getText()));
            }
        });
        beanRow.add(saveButton, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(beanRow, BorderLayout.CENTER);

        MouseAdapter select = new MouseAdapter() {
            // 同样用按下而不是单击：单击事件在 Windows 上会因按下时的光标抖动而丢失
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    // 点卡片空白处 = 切换到这个角色
                    listener.onSelect(slot);
                }
            }
        };
        addMouseListener(select);
        // Swing 事件不会冒泡，所以文字标签要单独挂一次，点名字也能切换角色
        bindRecursively(header, select);

        Dimension size = new Dimension(250, CARD_HEIGHT);
        setPreferredSize(size);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, CARD_HEIGHT));
        setMinimumSize(size);
    }

    /**
     * 当前是否被选中。
     *
     * @return 选中返回 true
     */
    public boolean isSelected() {
        return selected;
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
     * 更新显示用的功夫豆数值（保存成功后刷新）。
     *
     * @param bean 新值
     */
    public void setBean(long bean) {
        beanField.setText(Long.toString(bean));
    }

    /**
     * 锁定/解锁编辑控件（游戏运行期间要禁用）。
     *
     * @param enabled 是否可编辑
     */
    public void setEditingEnabled(boolean enabled) {
        this.editingEnabled = enabled;
        beanField.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /** 自绘卡片底色与描边（选中时加深并加一条左侧亮条）。 */
    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(selected ? new Color(0x1A, 0x37, 0x66) : Theme.CARD);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g.setColor(selected ? Theme.ACCENT : Theme.LINE);
        g.setStroke(new BasicStroke(selected ? 2f : 1f));
        g.drawRoundRect(0, 0, getWidth() - 2, getHeight() - 2, 14, 14);
        if (selected) {
            g.setColor(Theme.ACCENT);
            g.fillRoundRect(0, 12, 4, getHeight() - 24, 4, 4);
        }
        g.dispose();
    }

    /**
     * 给指定容器内的文字标签补挂鼠标监听（只挂 JLabel，不碰输入框与按钮）。
     */
    private void bindRecursively(Container container, MouseAdapter listener) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label) {
                label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                label.addMouseListener(listener);
            } else if (child instanceof Container nested && !(child instanceof JComponent jc && jc.isOpaque())) {
                bindRecursively(nested, listener);
            }
        }
    }

    private static long parse(String text) {
        try {
            return Long.parseLong(text.trim().replace(",", ""));
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
