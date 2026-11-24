package com.chengyi.xiewa.save.model;

/**
 * 存档的结构版本信息。
 *
 * <p>游戏 1.0.7（build 10011）在存档顶层写了：
 * <pre>save_version {last_client_version: V1.0.2, last_client_build: 10011,
 *               minimum_client_build: 10011}
 * schema_version: 3</pre>
 *
 * <p>修改器真正依赖的是 {@code schema_version}：它一变，说明存档字段布局可能改了，
 * 用旧代码硬写就有写坏的风险。{@code save_version} 里的客户端版本只用于提示。
 *
 * @param schemaVersion 存档结构版本（当前 3）
 * @param clientVersion 最后一次写这份存档的客户端版本，如 V1.0.2
 * @param clientBuild   最后一次写这份存档的客户端 build 号
 * @param minBuild      允许打开这份存档的最低 build 号
 */
public record SaveMeta(long schemaVersion, String clientVersion, long clientBuild, long minBuild) {

    /** 修改器认识（并实测过）的存档结构版本。 */
    public static final long SUPPORTED_SCHEMA = 3;

    /**
     * 起始收紧「存档水位」校验的客户端 build 号（1.0.9）。
     *
     * <p>这个版本起，写入时代数 +1 的存档会被游戏的水位守卫整份回滚
     * （内容全丢、原档转储 save.dat.corrupt）。修改器对 build ≥ 此值的存档
     * 改用「保持代数不变」的写入策略。
     */
    public static final long WATERMARK_TIGHT_BUILD = 10017;

    /**
     * 结构版本是否在支持范围内。
     *
     * <p>判定用「不超过」而不是「等于」：早期存档没有这个字段（读到 0），
     * 结构反而更简单，照常处理即可；而一旦<b>大于</b>支持版本，说明游戏加了新结构，
     * 字段布局可能变了，这时候继续硬写就有写坏的风险，必须停下来。
     *
     * @return 可以安全写入返回 true
     */
    public boolean schemaSupported() {
        return schemaVersion <= SUPPORTED_SCHEMA;
    }

    /**
     * 版本一句话，用于状态栏与 info 输出。
     *
     * @return 例如「客户端 V1.0.2 (build 10011) · 结构 v3」
     */
    public String summary() {
        String version = clientVersion == null || clientVersion.isBlank() ? "未知" : clientVersion;
        return "客户端 %s (build %d) · 结构 v%d".formatted(version, clientBuild, schemaVersion);
    }
}
