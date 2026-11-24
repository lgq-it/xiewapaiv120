package com.chengyi.xiewa.save.ui;

import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/**
 * 竖向堆叠、宽度跟随视口的容器（放滚动面板里用）。
 *
 * <p>普通 {@link JPanel} 放进 {@link javax.swing.JScrollPane} 时宽度取首选宽度，
 * 结果卡片会被挤在左边一条。实现 {@link Scrollable#getScrollableTracksViewportWidth()}
 * 让容器始终和视口同宽，卡片才能撑满。
 */
public class StackPanel extends JPanel implements Scrollable {

    /**
     * @param layout 布局管理器（通常是竖向 BoxLayout）
     */
    public StackPanel(LayoutManager layout) {
        super(layout);
    }

    /** 视口尺寸 = 首选尺寸。 */
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    /** 滚轮一格 18 像素。 */
    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 18;
    }

    /** 翻页留 40 像素重叠，方便定位。 */
    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(visibleRect.height - 40, 40);
    }

    /** 宽度始终跟随视口（卡片撑满的关键）。 */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /** 高度按内容增长，交给滚动条。 */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
