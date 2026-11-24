package com.chengyi.xiewa.save.core;

/**
 * 存档格式或校验失败时抛出。
 *
 * <p>属于「预期内」的错误（密钥不对、文件被截断、格式版本不符），
 * 调用方应当捕获后给出可读提示，而不是当作程序缺陷处理。
 */
public class SaveFormatException extends Exception {

    private static final long serialVersionUID = 1L;

    public SaveFormatException(String message) {
        super(message);
    }

    public SaveFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
