package com.chengyi.xiewa.save;

import com.chengyi.xiewa.save.ui.MainFrame;

/**
 * 图形界面入口（打包后的 exe 启动的就是这里）。
 *
 * <p>命令行入口是 {@link XiewaSaveApp}，两个入口共用同一套核心与业务代码。
 */
public final class XiewaEditorApp {

    private XiewaEditorApp() {
        throw new AssertionError("入口类不允许实例化");
    }

    /**
     * 程序入口。
     *
     * @param args 忽略
     */
    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        MainFrame.launch();
    }
}
