#!/usr/bin/env bash
# 只启动 Library demo（RESTRICTED），用于本地浏览器验证；用 setsid 脱离 SSH 会话存活。
set -u
export JAVA_HOME=/opt/java/jdk-17
export PATH=$JAVA_HOME/bin:$PATH
cd /var/ideaProjects/harken
pkill -9 -f LibraryDemoMain 2>/dev/null
fuser -k 8091/tcp 2>/dev/null
sleep 1
cd harken-demo-library
EXTRA_IPS="${1:-}"
setsid mvn -q exec:java -Dexec.args="/var/ideaProjects/harken/harken-demo-library/scripts 8091 ${EXTRA_IPS}" \
  > /tmp/libdemo-ui.log 2>&1 < /dev/null &
disown 2>/dev/null || true
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' http://127.0.0.1:8091/harken/api/health)
  if [ "$code" != "000" ]; then echo "LIB_UP http=$code"; exit 0; fi
  sleep 1
done
echo "LIB_FAILED"; tail -20 /tmp/libdemo-ui.log; exit 1
