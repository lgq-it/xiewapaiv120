package com.chengyi.xiewa.save.ui;

import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/**
 * 自适应换行的网格容器（放滚动面板里用）。
 *
 * <p>配合 {@link WrapLayout}：宽度跟随视口，每行能放几个由实际宽度决定，
 * 高度按行数增长，于是"窗口拉宽 → 一行多放几个格子"就自然成立了。
 */
public class WrapPanel extends JPanel implements Scrollable {

    /**
     * 构造。
     */
    public WrapPanel() {
        super(new WrapLayout(java.awt.FlowLayout.LEFT, 8, 8));
        setOpaque(true);
    }

    /** 视口尺寸 = 首选尺寸。 */
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    /** 滚轮一格 24 像素（约一格子高度的一半）。 */
    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 24;
    }

    /** 翻页留 60 像素重叠，方便定位。 */
    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(visibleRect.height - 60, 60);
    }

    /** 宽度跟随视口：WrapLayout 才能按实际宽度决定每行几个。 */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /** 高度按行数增长，交给滚动条。 */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
