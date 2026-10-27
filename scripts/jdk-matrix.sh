#!/usr/bin/env bash
# ============================================================================
# jdk-matrix.sh —— 多 JDK 测试矩阵（M5 #3）
#
# 目的：把「支持 JDK 17/21/25」从一句文档承诺变成一条命令。
# 每次改完核心代码跑一遍，比在三个终端里手工切 JAVA_HOME 可靠。
#
# 用法：
#   bash scripts/jdk-matrix.sh                    # 自动探测常见路径
#   JDKS="/opt/java/jdk-17 /opt/java/jdk-21" bash scripts/jdk-matrix.sh
#
# 判定：
#   * 每个 JDK 跑 `mvn -B clean test`，只看 BUILD 结果与汇总行；
#   * 任一 JDK 失败则整体退出码 1，并打印失败 JDK 的末尾日志；
#   * 全部通过时打印一张对比表（各模块测试数应完全一致 —— 不一致说明
#     某套 JDK 上有条件跳过，需要人看一眼）。
# ============================================================================
set -u

cd "$(dirname "$0")/.." || exit 2
ROOT=$(pwd)

if [ -n "${JDKS:-}" ]; then
    CANDIDATES=$JDKS
else
    # 顺序即优先级：先用 PATH 里的 java，再按常见安装位置探测
    CANDIDATES=""
    if command -v javac >/dev/null 2>&1; then
        CANDIDATES="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    fi
    for d in /opt/java/jdk-* /usr/lib/jvm/java-*-openjdk-* /usr/lib/jvm/jdk-* \
             "$HOME"/.sdkman/candidates/java/*; do
        [ -x "$d/bin/javac" ] && CANDIDATES="$CANDIDATES $d"
    done
fi

FAILED=""
SUMMARY=""
CHECKED=0

SEEN=""
for JDK in $CANDIDATES; do
    [ -x "$JDK/bin/javac" ] || continue
    # 去重：/usr/lib/jvm/java-21-openjdk-amd64 与 /opt/java/jdk-21 常是同一个 JDK 的
    # 两个路径（符号链接），不去重会把同一套 JDK 跑两遍、在汇总表里重复占两行
    REAL=$(readlink -f "$JDK")
    case " $SEEN " in *" $REAL "*) continue ;; esac
    SEEN="$SEEN $REAL"
    CHECKED=$((CHECKED + 1))
    VER=$("$JDK/bin/java" -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')
    echo "=============================================================="
    echo "  JDK $VER  ($JDK)"
    echo "=============================================================="

    OUT=$(JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" mvn -B clean test 2>&1)
    RC=$?

    if [ $RC -ne 0 ]; then
        echo "  ✗ FAILED"
        FAILED="$FAILED $VER"
        # 只打印真正有用的行，避免几百行噪音
        printf '%s\n' "$OUT" | grep -E 'ERROR|FAIL|Tests run:.*(Failures: [1-9]|Errors: [1-9])' | head -20
        continue
    fi

    # 汇总：各模块 Tests run 的最后一条（跳过 surefire 的分模块明细）
    # 只取「模块小计」行：按类的明细行末尾带 ' -- in com.xxx'，要把它们排除，
    # 否则同一批测试会被计两次（实测 223 个测试会算成 514）
    TOTALS=$(printf '%s\n' "$OUT" | grep -E '^\[(INFO|WARNING)\] Tests run: [0-9]+, Failures: 0, Errors: 0' \
             | grep -v ' -- in ' \
             | sed -E 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+).*/\1 \4/' \
             | awk '{t+=$1; s+=$2} END {printf "%d passed, %d skipped", t-s, s}')
    echo "  ✓ BUILD SUCCESS  合计 $TOTALS"
    SUMMARY="$SUMMARY
| $VER | $TOTALS |"
done

echo
echo "=============================================================="
if [ -z "$FAILED" ]; then
    echo "  全部通过（$CHECKED 套 JDK）"
    printf '| JDK | 结果 |\n|---|---|%s\n' "$SUMMARY"
    exit 0
fi
echo "  失败：$FAILED"
exit 1
