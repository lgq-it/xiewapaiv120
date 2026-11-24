package com.chengyi.xiewa.save.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * 会自动换行的 {@link FlowLayout}。
 *
 * <p>背包格子必须「按窗口宽度自适应每行几个」，而 Swing 原生布局都不做这件事：
 * FlowLayout 换行了但不算高度，GridLayout 列数固定。这里重写尺寸计算，
 * 让容器在给定宽度下返回正确的首选高度，滚动面板才能正常滚。
 */
public class WrapLayout extends FlowLayout {

    /**
     * @param align 对齐方式（{@link FlowLayout#LEFT} 等）
     * @param hgap  水平间距
     * @param vgap  垂直间距
     */
    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    /** 首选尺寸：按目标宽度换行后算总高度（这是“自动换行”的核心）。 */
    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    /** 最小尺寸：同上，但按组件的 minimumSize 算。 */
    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            Container container = target;
            while (targetWidth == 0 && container.getParent() != null) {
                container = container.getParent();
                targetWidth = container.getSize().width;
            }
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
            int maxWidth = targetWidth - horizontalInsetsAndGap;

            Dimension dimension = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            for (int i = 0; i < target.getComponentCount(); i++) {
                Component component = target.getComponent(i);
                if (!component.isVisible()) {
                    continue;
                }
                Dimension size = preferred ? component.getPreferredSize() : component.getMinimumSize();
                if (rowWidth + size.width > maxWidth && rowWidth > 0) {
                    addRow(dimension, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) {
                    rowWidth += hgap;
                }
                rowWidth += size.width;
                rowHeight = Math.max(rowHeight, size.height);
            }
            addRow(dimension, rowWidth, rowHeight);

            dimension.width += horizontalInsetsAndGap;
            dimension.height += insets.top + insets.bottom + vgap * 2;

            // 放在滚动面板里时宽度由视口决定，避免布局抖动
            Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scrollPane != null && target.isValid()) {
                dimension.width -= (hgap + 1);
            }
            return dimension;
        }
    }

    private void addRow(Dimension dimension, int rowWidth, int rowHeight) {
        dimension.width = Math.max(dimension.width, rowWidth);
        if (dimension.height > 0) {
            dimension.height += getVgap();
        }
        dimension.height += rowHeight;
    }
}
