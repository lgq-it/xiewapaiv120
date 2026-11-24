package com.chengyi.xiewa.save.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JTextField;

/**
 * 带占位提示的输入框。
 *
 * <p>提示文字通过 {@code putClientProperty("JTextField.placeholderText", …)} 设置的写法
 * 只在 FlatLaf 等第三方外观下生效；本项目用 Metal，得自己画。规则与主流实现一致：
 * <b>内容为空且没有焦点</b>时显示灰色提示，一旦开始输入或点进去就消失。
 */
public class PlaceholderField extends JTextField {

    private static final long serialVersionUID = 1L;

    /** 占位提示文字。 */
    private final String placeholder;

    /**
     * @param columns     列宽
     * @param placeholder 提示文字
     */
    public PlaceholderField(int columns, String placeholder) {
        super(columns);
        this.placeholder = placeholder;
    }

    /** 先按正常流程画输入框，再在空且未聚焦时补画灰色提示。 */
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getText().isEmpty() && !isFocusOwner() && placeholder != null && !placeholder.isEmpty()) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setFont(getFont().deriveFont(Font.PLAIN));
            g.setColor(new Color(0x5A, 0x6E, 0x8C));
            // 垂直居中：基线 = 高度一半 + 字形高度的一半再往上提一点
            java.awt.FontMetrics metrics = g.getFontMetrics();
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(placeholder, getMargin().left + 2, y);
            g.dispose();
        }
    }
}
