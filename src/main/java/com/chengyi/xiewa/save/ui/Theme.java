package com.chengyi.xiewa.save.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;

/**
 * 全局视觉：深海军蓝底 + 亮蓝强调色。
 *
 * <p>Swing 默认的 Metal 主题是浅灰工业风，跟游戏界面放在一起很突兀，所以这里统一改色板，
 * 并封装几个常用控件的构造方法，避免每处都重复设色。
 */
public final class Theme {

    /** 窗口底色。 */
    public static final Color BG = new Color(0x06, 0x14, 0x30);

    /** 面板底色（比窗口略亮一档）。 */
    public static final Color SURFACE = new Color(0x0B, 0x1C, 0x38);

    /** 卡片底色。 */
    public static final Color CARD = new Color(0x11, 0x28, 0x4C);

    /** 卡片悬停色。 */
    public static final Color CARD_HOVER = new Color(0x17, 0x35, 0x62);

    /** 强调色（按钮、选中边框）。 */
    public static final Color ACCENT = new Color(0x4C, 0x8D, 0xFF);

    /** 金色（货币数字）。 */
    public static final Color GOLD = new Color(0xE8, 0xB8, 0x4B);

    /** 正文色。 */
    public static final Color TEXT = new Color(0xE6, 0xED, 0xF7);

    /** 次要文字。 */
    public static final Color MUTED = new Color(0x8F, 0xA3, 0xBF);

    /** 危险/错误。 */
    public static final Color DANGER = new Color(0xFF, 0x6B, 0x6B);

    /** 成功。 */
    public static final Color OK = new Color(0x5B, 0xD6, 0x9A);

    /** 分隔线。 */
    public static final Color LINE = new Color(0x1E, 0x36, 0x5C);

    /** 常用字号。 */
    public static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);

    /** 标题字号。 */
    public static final Font FONT_TITLE = new Font("Microsoft YaHei UI", Font.BOLD, 15);

    /** 小字号。 */
    public static final Font FONT_SMALL = new Font("Microsoft YaHei UI", Font.PLAIN, 11);

    /** 数字字号。 */
    public static final Font FONT_NUMBER = new Font("Consolas", Font.BOLD, 15);

    private Theme() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 安装全局主题（须在创建任何窗口之前调用）。
     */
    /**
     * 窗口图标（jar 内打包的 {@code /icon.png}）。
     *
     * <p>exe 文件本身的图标由 jpackage {@code --icon} 写进资源；这里管的是
     * 窗口左上角和任务栏上显示的那张。资源缺失时返回 null，窗口会退回默认图标。
     *
     * @return 图标；加载失败返回 null
     */
    public static Image windowIcon() {
        var url = Theme.class.getResource("/icon.png");
        return url == null ? null : new ImageIcon(url).getImage();
    }

    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认主题也能跑，只是配色不一致
        }        UIManager.put("Panel.background", SURFACE);
        UIManager.put("Viewport.background", BG);
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", FONT);
        UIManager.put("Button.font", FONT);
        UIManager.put("Button.background", CARD);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.focus", new Color(0, 0, 0, 0));
        UIManager.put("TextField.font", FONT);
        UIManager.put("TextField.background", new Color(0x08, 0x18, 0x33));
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("TextField.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        UIManager.put("ComboBox.font", FONT);
        UIManager.put("ComboBox.background", CARD);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", ACCENT);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("List.font", FONT);
        UIManager.put("List.background", new Color(0x08, 0x18, 0x33));
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", ACCENT);
        UIManager.put("List.selectionForeground", Color.WHITE);
        UIManager.put("ScrollBar.thumb", new Color(0x24, 0x42, 0x6E));
        UIManager.put("ScrollBar.track", BG);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ToolTip.background", CARD);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.font", FONT_SMALL);
        UIManager.put("CheckBox.background", SURFACE);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.font", FONT);
        UIManager.put("RadioButton.background", SURFACE);
        UIManager.put("RadioButton.foreground", TEXT);
        UIManager.put("SplitPane.background", BG);
        UIManager.put("Dialog.background", SURFACE);
    }

    /**
     * 造一个纯色面板。
     *
     * @param color 底色
     * @return 面板
     */
    public static JPanel panel(Color color) {
        JPanel panel = new JPanel();
        panel.setBackground(color);
        return panel;
    }

    /**
     * 造一个带内边距的面板。
     *
     * @param color  底色
     * @param top    上边距
     * @param left   左边距
     * @param bottom 下边距
     * @param right  右边距
     * @return 面板
     */
    public static JPanel padded(Color color, int top, int left, int bottom, int right) {
        JPanel panel = panel(color);
        panel.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
        return panel;
    }

    /**
     * 造一个标签。
     *
     * @param text  文本
     * @param color 颜色
     * @param font  字体
     * @return 标签
     */
    public static JLabel label(String text, Color color, Font font) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(font);
        return label;
    }

    /**
     * 主按钮（强调色填充）。
     *
     * @param text 文本
     * @return 按钮
     */
    public static JButton primary(String text) {
        JButton button = baseButton(text);
        button.setBackground(ACCENT);
        button.setForeground(Color.WHITE);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 1),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        return button;
    }

    /**
     * 次按钮（描边风格）。
     *
     * @param text 文本
     * @return 按钮
     */
    public static JButton ghost(String text) {
        JButton button = baseButton(text);
        button.setBackground(CARD);
        button.setForeground(TEXT);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        return button;
    }

    private static JButton baseButton(String text) {
        JButton button = new JButton(text);
        button.setFont(FONT);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        return button;
    }

    /**
     * 造一个数字输入框（右对齐，去掉默认的虚线焦点框）。
     *
     * @param columns 列宽
     * @return 输入框
     */
    public static JTextField numberField(int columns) {
        JTextField field = new JTextField(columns);
        field.setFont(FONT_NUMBER);
        field.setForeground(GOLD);
        field.setBackground(new Color(0x08, 0x18, 0x33));
        field.setCaretColor(GOLD);
        field.setHorizontalAlignment(JTextField.RIGHT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        return field;
    }

    /**
     * 造一个搜索框（带占位提示）。
     *
     * <p>Metal 外观不认 FlatLaf 的 placeholder 客户端属性，所以这里直接返回
     * 自绘的 {@link PlaceholderField}，空且未聚焦时显示灰色提示。
     *
     * @param columns 列宽
     * @param hint    占位提示
     * @return 输入框
     */
    public static JTextField searchField(int columns, String hint) {
        PlaceholderField field = new PlaceholderField(columns, hint);
        field.setFont(FONT);
        field.setForeground(TEXT);
        field.setBackground(new Color(0x08, 0x18, 0x33));
        field.setCaretColor(TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        return field;
    }

    /**
     * 统一滚动条样式。
     *
     * @param scroll 滚动面板
     * @return 原对象，便于链式使用
     */
    public static JScrollPane style(JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);
        scroll.setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getHorizontalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    /**
     * 给组件加一圈细边框。
     *
     * @param component 组件
     * @param color     边框色
     * @return 原对象
     */
    public static <T extends JComponent> T outline(T component, Color color) {
        Border border = BorderFactory.createLineBorder(color, 1);
        component.setBorder(border);
        return component;
    }

    /**
     * 固定尺寸。
     *
     * @param component 组件
     * @param width     宽
     * @param height    高
     * @return 原对象
     */
    public static <T extends JComponent> T size(T component, int width, int height) {
        Dimension dimension = new Dimension(width, height);
        component.setPreferredSize(dimension);
        component.setMinimumSize(dimension);
        return component;
    }

    /**
     * 让组件不参与布局拉伸。
     *
     * @param component 组件
     * @return 原对象
     */
    public static <T extends Component> T fixed(T component) {
        if (component instanceof JComponent jc) {
            Dimension preferred = jc.getPreferredSize();
            jc.setMaximumSize(preferred);
            jc.setMinimumSize(preferred);
        }
        return component;
    }
}
