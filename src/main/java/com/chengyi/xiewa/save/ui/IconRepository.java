package com.chengyi.xiewa.save.ui;

import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/**
 * 物品图标仓库。
 *
 * <p>图标是从游戏资源包里提取的 2745 张 PNG，随程序打成 {@code icons.zip}。
 * 因为 jar 内的资源无法随机访问，首次运行时会把它复制到本机缓存目录，
 * 之后用 {@link ZipFile} 按需取图——比一次性解压 2700 个文件干净得多。
 *
 * <p>缓存用 LRU 限制条目数，避免滚动大背包时把整包图都读进内存。
 */
public final class IconRepository implements AutoCloseable {

    /** 图标显示边长（像素）。 */
    public static final int ICON_SIZE = 44;

    private static final String RESOURCE = "/icons.zip";

    private static final int CACHE_LIMIT = 600;

    private final ZipFile zip;

    private final Map<Integer, ImageIcon> cache = new LinkedHashMap<>(256, 0.75f, true) {
        // 超过缓存上限时淘汰最久没用的那张图
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, ImageIcon> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private ImageIcon placeholder;

    /** 全局共享的占位图：缺图时几百个格子共用一张，不该每次重画一遍。 */
    private static ImageIcon sharedPlaceholder;

    private IconRepository(ZipFile zip) {
        this.zip = zip;
    }

    /**
     * 占位图（图标缺失时使用），全局共享一份。
     *
     * @return 占位图标
     */
    public static synchronized ImageIcon placeholderIcon() {
        if (sharedPlaceholder == null) {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    ICON_SIZE, ICON_SIZE, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(0x1E, 0x36, 0x5C));
            g.fillRoundRect(2, 2, ICON_SIZE - 4, ICON_SIZE - 4, 10, 10);
            g.setColor(new java.awt.Color(0x5B, 0x74, 0x96));
            g.setFont(new java.awt.Font("Microsoft YaHei UI", java.awt.Font.BOLD, 18));
            g.drawString("?", ICON_SIZE / 2 - 5, ICON_SIZE / 2 + 7);
            g.dispose();
            sharedPlaceholder = new ImageIcon(image);
        }
        return sharedPlaceholder;
    }

    /**
     * 打开图标仓库。
     *
     * @return 仓库实例
     * @throws IOException 资源缺失或缓存目录不可写
     */
    public static IconRepository open() throws IOException {
        Path cached = cacheDirectory().resolve("icons.zip");
        if (!Files.isRegularFile(cached) || Files.size(cached) < 1024 || versionMismatch(cached)) {
            try (InputStream in = IconRepository.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IOException("程序内缺少图标资源：" + RESOURCE);
                }
                Files.createDirectories(cached.getParent());
                Path temp = cached.resolveSibling("icons.zip.tmp");
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return new IconRepository(new ZipFile(cached.toFile()));
    }

    /**
     * 校验本机缓存包的版本号；不一致（或缓存损坏）时让调用方重新拷贝。
     *
     * <p>图标包升级后（比如补齐了 645 件装备图标），老缓存如果只看「文件存在」
     * 就永远不会刷新，所以 zip 里带一个 {@code icons.ver} 版本号对齐校验。
     *
     * @param cached 本机缓存 zip 路径
     * @return 版本不一致或缓存损坏返回 true
     */
    private static boolean versionMismatch(Path cached) {
        String expected = iconVersion();
        if (expected == null) {
            return false; // 程序内没有版本号资源就沿用旧行为
        }
        try (ZipFile zip = new ZipFile(cached.toFile())) {
            ZipEntry ver = zip.getEntry("icons.ver");
            if (ver == null) {
                return true;
            }
            try (InputStream in = zip.getInputStream(ver)) {
                return !expected.equals(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            return true;
        }
    }

    /** 读取程序内图标包版本号；缺失时返回 null。 */
    private static String iconVersion() {
        try (InputStream in = IconRepository.class.getResourceAsStream("/icons.ver")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 取某件物品的图标。
     *
     * @param itemId 物品 ID
     * @return 图标（缺失时返回占位图，不为 null）
     */
    public ImageIcon icon(int itemId) {
        ImageIcon cached = cache.get(itemId);
        if (cached != null) {
            return cached;
        }
        ImageIcon loaded = load(itemId);
        cache.put(itemId, loaded);
        return loaded;
    }

    private ImageIcon load(int itemId) {
        ZipEntry entry = zip.getEntry(itemId + ".png");
        if (entry == null) {
            return placeholder();
        }
        try (InputStream in = zip.getInputStream(entry)) {
            Image image = ImageIO.read(in);
            if (image == null) {
                return placeholder();
            }
            return new ImageIcon(image.getScaledInstance(
                    ICON_SIZE, ICON_SIZE, Image.SCALE_SMOOTH));
        } catch (IOException e) {
            return placeholder();
        }
    }

    private ImageIcon placeholder() {
        if (placeholder == null) {
            placeholder = placeholderIcon();
        }
        return placeholder;
    }

    /** 释放 ZipFile 句柄（窗口关闭时调用，否则文件一直被占用）。 */
    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }

    /**
     * 图标缓存目录：优先 {@code %LOCALAPPDATA%\XiewaSaveEditor}，其次临时目录。
     *
     * @return 目录
     */
    public static Path cacheDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local == null || local.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(local);
        return base.resolve("XiewaSaveEditor");
    }
}
