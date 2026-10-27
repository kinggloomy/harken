#!/usr/bin/env bash
# ============================================================================
# bench.sh —— 性能基准（M5 #7）
#
# 量四件真正会被问到的事：
#   1. 首次执行（含编译）有多慢 —— 决定「打开就能用」的观感
#   2. 同一脚本重复执行 —— 验证编译缓存命中（缓存不命中这里会掉到首次的量级）
#   3. 每次都是新脚本（必然重新编译）—— 最坏情况
#   4. Metaspace 增长 —— 500 个不同脚本后会不会无限涨（类永远不卸载）
#
# 不做微基准框架（JMH）：想验证的是「HTTP 端到端 + 真实 Groovy 编译」这个整体，
# 而不是某个纳秒级操作。网络与序列化本来就在用户感受到的延迟里。
#
# 用法（仓库根目录）：
#   mvn -q -DskipTests package && bash scripts/bench.sh
# ============================================================================
set -u

cd "$(dirname "$0")/.." || exit 2
JAR="harken-demo/target/harken-demo-1.0.0-SNAPSHOT.jar"
JAVA_BIN="${HARKEN_JAVA:-java}"
JCMD_BIN="${HARKEN_JCMD:-jcmd}"
JSTAT_BIN="${HARKEN_JSTAT:-jstat}"
PORT="${BENCH_PORT:-18090}"
BASE="http://127.0.0.1:$PORT"

[ -f "$JAR" ] || { echo "找不到 $JAR，先 mvn -q -DskipTests package" >&2; exit 2; }

cleanup() {
    [ -n "${APP_PID:-}" ] && kill -9 "$APP_PID" 2>/dev/null
    fuser -k "${PORT}/tcp" 2>/dev/null
}
trap cleanup EXIT

echo "=== 启动基准实例（端口 $PORT）==="
nohup "$JAVA_BIN" -Dfile.encoding=UTF-8 -jar "$JAR" \
    --server.port="$PORT" --demo.exit-after=false \
    > /tmp/bench-app.log 2>&1 < /dev/null &
APP_PID=$!

for _ in $(seq 1 90); do
    code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$BASE/harken/api/health")
    [ "$code" != "000" ] && break
    sleep 1
done
[ "$code" = "000" ] && { echo "启动失败"; tail -20 /tmp/bench-app.log; exit 2; }

TOKEN=$(curl -s -m 10 -X POST "$BASE/harken/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"demo-admin-pw"}' \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -z "$TOKEN" ] && { echo "登录失败"; exit 2; }

# 一次执行，回显耗时（秒，保留 4 位）
run_once() {
    local script=$1
    curl -s -o /dev/null -m 60 -w '%{time_total}' -X POST "$BASE/harken/api/execute" \
        -H "X-Harken-Session: $TOKEN" -H 'Content-Type: application/json' \
        --data-binary "{\"script\":\"$script\",\"commit\":false}"
}

avg() { awk '{s+=$1; n++} END {if (n>0) printf "%.1f", s/n*1000; else print "0"}'; }

# Metaspace 已用（KB）。
# 取 jcmd GC.heap_info 里那一行 "Metaspace used 55043K, committed ..."：
# 这一行 JDK 11 起格式稳定，而且和堆信息一起输出，不必额外的 jstat 依赖。
metaspace_kb() {
    local v
    v=$("$JCMD_BIN" "$APP_PID" GC.heap_info 2>/dev/null \
        | awk '/Metaspace/ {for (i=1;i<=NF;i++) if ($i=="used") {gsub(/K,?$/,"",$(i+1)); print $(i+1); exit}}')
    case "$v" in ''|*[!0-9]*) echo "n/a" ;; *) echo "$v" ;; esac
}

echo
echo "=== 1. 首次执行（冷启动，含 Groovy 编译 + 类加载）==="
FIRST=""
for i in 1 2 3; do
    t=$(run_once "orderService.count() + $i * 0")
    FIRST="$FIRST$t
"
    printf '  第 %d 次：%s ms\n' "$i" "$(awk -v t="$t" 'BEGIN{printf "%.1f", t*1000}')"
done

echo
echo "=== 2. 同一脚本重复 50 次（应命中编译缓存）==="
WARM=""
for _ in $(seq 1 50); do WARM="$WARM$(run_once 'orderService.count()')
"; done
printf '%s' "$WARM" | avg | xargs -I{} printf '  平均：%s ms\n' {}

echo
echo "=== 3. 每次都是新脚本 30 次（必然重新编译，最坏情况）==="
COLD=""
for i in $(seq 1 30); do
    # 用注释让源码 hash 不同，从而绕过编译缓存
    COLD="$COLD$(run_once "// variant $i\norderService.count()")
"
done
printf '%s' "$COLD" | avg | xargs -I{} printf '  平均：%s ms\n' {}

echo
echo "=== 4. Metaspace：两批各 500 个互不相同的脚本 ==="
# 测两批而不是一批：一批只能得到「涨了多少」，两批才能看出「每批涨得一样多（线性）」
# 还是「第二批几乎不涨（收敛）」。这个区别决定了长期运行要不要定期重启。
MS0=$(metaspace_kb)
for i in $(seq 1 500); do
    run_once "// probe-a $i\norderService.count()" > /dev/null
done
MS1=$(metaspace_kb)
for i in $(seq 1 500); do
    run_once "// probe-b $i\norderService.count()" > /dev/null
done
MS2=$(metaspace_kb)
echo "  起点      ：$MS0 KB"
echo "  第 1 批后 ：$MS1 KB（+$((MS1 - MS0))）"
echo "  第 2 批后 ：$MS2 KB（+$((MS2 - MS1))）"
if [ "$MS1" != "n/a" ] && [ "$MS2" != "n/a" ] && [ "$MS1" -gt "$MS0" ]; then
    D1=$((MS1 - MS0)); D2=$((MS2 - MS1))
    if [ "$D2" -lt $((D1 / 2)) ]; then
        echo "  结论      ：收敛（第二批增长不足第一批一半）"
    else
        echo "  结论      ：**线性增长**，约 $((D2 / 500)) KB/脚本"
        echo "              Groovy 每段新脚本生成一个类，类永不卸载（DEV-PROGRESS 事实 5）。"
        echo "              缓解：限制 maxScriptLength / 复用脚本（受限模式天然复用）/ 定期重启。"
    fi
fi

echo
echo "=== 5. 能力清单（Agent 每次启动都会拉一次）==="
SIZE=$(curl -s -m 30 "$BASE/harken/ai/capabilities" -H "Authorization: Bearer demo-ai-token" | wc -c)
TIME=$(curl -s -o /dev/null -m 30 -w '%{time_total}' "$BASE/harken/ai/capabilities" \
        -H "Authorization: Bearer demo-ai-token")
printf '  大小：%s 字节（未压缩）\n' "$SIZE"
printf '  耗时：%s ms\n' "$(awk -v t="$TIME" 'BEGIN{printf "%.1f", t*1000}')"

echo
echo "=== 6. JVM 内存 ==="
"$JCMD_BIN" "$APP_PID" GC.heap_info 2>/dev/null | head -4 || echo "  （jcmd 不可用）"

echo
echo "基准完成。机器信息：$(uname -m) / $(nproc 2>/dev/null || echo '?') 核 / $(free -m 2>/dev/null | awk '/^Mem:/{print $2" MB"}')"
