#!/usr/bin/env bash
#
# 一键构建：编译 → 打 fat jar → jpackage 出绿色版 exe 文件夹
#
# 用法（Git Bash / IDEA 终端）：
#   ./build-exe.sh              # 完整构建，产物在 dist/chengyi-updatepai/
#   ./build-exe.sh --jar-only   # 只打 jar，不生成 exe（改代码后快速验证）
#
# 依赖：JDK 21（含 jpackage）、Maven。两者路径写死在本文件顶部，换机器时改这里即可。

set -euo pipefail

# ---------------------------------------------------------------- 可配置项
APP_NAME="chengyi-updatepai"
APP_VERSION="1.0.0"
MAIN_CLASS="com.chengyi.xiewa.save.XiewaEditorApp"
MAIN_JAR="xiewa-save.jar"

JDK_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-21}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$PROJECT_DIR/dist"
STAGE_DIR="$PROJECT_DIR/target/jpkg"

# ---------------------------------------------------------------- 环境检查
if [ ! -x "$JDK_HOME/bin/jpackage.exe" ] && [ ! -x "$JDK_HOME/bin/jpackage" ]; then
  echo "✗ 找不到 jpackage：$JDK_HOME" >&2
  echo "  请设置 JAVA_HOME 指向 JDK 21（不是 JRE，jpackage 只在 JDK 里）" >&2
  exit 1
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JDK_HOME/bin:$PATH"
echo "· JDK      : $("$JDK_HOME/bin/java" -version 2>&1 | head -1)"

if command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
else
  MVN="$(ls -d "/c/Program Files/JetBrains"/*/plugins/maven/lib/maven3/bin/mvn.cmd 2>/dev/null | head -1)"
  if [ -z "$MVN" ]; then
    echo "✗ 找不到 mvn，也没有 IDEA 内置的 maven" >&2
    exit 1
  fi
fi
echo "· Maven    : $MVN"

# ---------------------------------------------------------------- 编译打包
cd "$PROJECT_DIR"
echo
echo "▶ [1/3] mvn clean package"
"$MVN" -q clean package

if [ ! -f "target/$MAIN_JAR" ]; then
  echo "✗ 没有生成 target/$MAIN_JAR" >&2
  exit 1
fi
echo "✓ jar 就绪：target/$MAIN_JAR ($(du -h "target/$MAIN_JAR" | cut -f1))"

if [ "${1:-}" = "--jar-only" ]; then
  echo
  echo "✓ 仅打 jar 完成。可直接运行："
  echo "  \"$JAVA_HOME/bin/java\" -jar target/$MAIN_JAR"
  exit 0
fi

# ---------------------------------------------------------------- 组装 jpackage 输入
# jpackage 的 --input 会把目录里所有文件都收进 app/，
# 而 target 里还有 original-xiewa-save.jar 等杂物，必须用只含目标 jar 的暂存目录。
echo
echo "▶ [2/3] 准备 jpackage 输入目录"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"
cp "target/$MAIN_JAR" "$STAGE_DIR/"

# ---------------------------------------------------------------- 生成 exe
echo
echo "▶ [3/3] jpackage 生成绿色版"
rm -rf "$DIST_DIR/$APP_NAME"
mkdir -p "$DIST_DIR"
"$JDK_HOME/bin/jpackage" \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input "$STAGE_DIR" \
  --main-jar "$MAIN_JAR" \
  --main-class "$MAIN_CLASS" \
  --icon "$PROJECT_DIR/design/icon.ico" \
  --dest "$DIST_DIR" \
  --java-options "-Dfile.encoding=UTF-8"

# ---------------------------------------------------------------- 收尾
OUT_DIR="$DIST_DIR/$APP_NAME"
echo
echo "✓ 构建完成：$OUT_DIR"
ls -lh "$OUT_DIR" | sed 's/^/    /'
