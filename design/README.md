# 图标设计资产

当前图标：**功夫派猴子头像**（用户提供的 favicon.ico 高清化而来）。

- `icon.ico` —— exe 文件的图标（jpackage `--icon` 用的就是它，16~256 七档尺寸）
- `icon.png` —— 256x256 源图，打包进 `src/main/resources/icon.png`（窗口/任务栏图标）
- `monkey_hd_256.png` —— 高清化成品（AI 修锐后的 256px 原图）
- `make_icon.py` —— 备用的金剑图标生成脚本（想换回手绘风格时跑它）

高清化流程（favicon 原图最大只有 64x64）：
1. 取 ico 里最大的 64x64 帧
2. LANCZOS 放大到 256 + UnsharpMask 锐化
3. AI 图像增强（enhance）去模糊/锯齿 → `monkey_hd_256.png`
4. 由 256 缩放生成七档尺寸的 `icon.ico`

重新打包时 jpackage 记得带 `--icon design/icon.ico`。
