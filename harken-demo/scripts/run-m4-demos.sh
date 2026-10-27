#!/usr/bin/env bash
# 启动两个 demo（Library=RESTRICTED@8091, Starter=FULL@18080），等就绪后跑 M4 冒烟，最后清理进程。
#
# 注意：8090 在这台机器上被另一个应用（wtx-copy）占用，本脚本绝不触碰 8090，
# 也不对 8090 做 fuser -k，避免误杀别人的进程。
set -u
cd /var/ideaProjects/harken
export JAVA_HOME=/opt/java/jdk-17
export PATH=$JAVA_HOME/bin:$PATH

LIB_PORT=8091
ST_PORT=18080

echo "=== 清理自家旧进程（只动 8091/18080） ==="
pkill -9 -f 'LibraryDemoMain' 2>/dev/null
pkill -9 -f 'harken-demo-1.0.0-SNAPSHOT' 2>/dev/null
pkill -9 -f 'exec:java' 2>/dev/null
for p in $LIB_PORT $ST_PORT; do
  fuser -k ${p}/tcp 2>/dev/null || true
done
sleep 2

# 端口占用预检：如果 8091/18080 还被别人占着，直接退出，不要盲目往下跑
for p in $LIB_PORT $ST_PORT; do
  if ss -ltn 2>/dev/null | grep -q ":${p} "; then
    echo "FATAL: 端口 ${p} 仍被占用，放弃启动（避免冒烟打错实例）"
    ss -ltnp 2>/dev/null | grep ":${p} "
    exit 2
  fi
done

rm -f /tmp/libdemo.log /tmp/starterdemo.log
( cd harken-demo-library && nohup mvn exec:java -Dexec.args="/var/ideaProjects/harken/harken-demo-library/scripts ${LIB_PORT}" > /tmp/libdemo.log 2>&1 < /dev/null & )
( cd harken-demo && nohup /opt/java/jdk-17/bin/java -jar target/harken-demo-1.0.0-SNAPSHOT.jar --server.port=${ST_PORT} > /tmp/starterdemo.log 2>&1 < /dev/null & )

wait_up() {
  local url=$1 name=$2
  for i in $(seq 1 90); do
    local code
    code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$url")
    if [ "$code" != "000" ]; then echo "$name up (http $code)"; return 0; fi
    sleep 1
  done
  echo "$name NOT up after 90s"; return 1
}

wait_up "http://127.0.0.1:${LIB_PORT}/harken/api/health" LIB || { echo '--- lib log ---'; tail -30 /tmp/libdemo.log; }
wait_up "http://127.0.0.1:${ST_PORT}/harken/api/health" ST || { echo '--- starter log ---'; tail -30 /tmp/starterdemo.log; }

echo '=== library log tail ==='; tail -12 /tmp/libdemo.log
echo '=== starter log tail ==='; tail -12 /tmp/starterdemo.log

echo '=== RUN M4 SMOKE ==='
bash harken-demo/scripts/m4-smoke.sh "http://127.0.0.1:${LIB_PORT}" "http://127.0.0.1:${ST_PORT}"
RC=$?
echo "SMOKE_EXIT=$RC"

# 清理：杀掉两个 demo 进程，保持远端干净（依旧只动 8091/18080）
pkill -9 -f 'LibraryDemoMain' 2>/dev/null
pkill -9 -f 'harken-demo-1.0.0-SNAPSHOT' 2>/dev/null
pkill -9 -f 'exec:java' 2>/dev/null
for p in $LIB_PORT $ST_PORT; do
  fuser -k ${p}/tcp 2>/dev/null || true
done
sleep 1
echo '=== cleaned up ==='
exit $RC
