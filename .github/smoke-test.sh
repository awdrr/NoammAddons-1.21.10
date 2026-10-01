#!/usr/bin/env bash
# Temporary 1.21.11 porting aid: boots the client under Xvfb with the mixin audit hook enabled
# (forces every mixin to apply), then reports injection failures. Remove once the port is verified.
set +e
sudo apt-get update -qq >/dev/null && sudo apt-get install -y -qq xvfb libgl1-mesa-dri >/dev/null
export JAVA_TOOL_OPTIONS="-Dnoammaddons.mixinAudit=true"
timeout 900 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient --console=plain > run.log 2>&1 &
PID=$!
for _ in $(seq 1 170); do
  grep -q "NOAMM_MIXIN_AUDIT" run.log && break
  kill -0 $PID 2>/dev/null || break
  sleep 5
done
sleep 15
kill $PID 2>/dev/null; pkill -f runClient 2>/dev/null; pkill -f KnotClient 2>/dev/null
echo "===== smoke result"
grep -nE "NOAMM_|Mixin apply|[Mm]ixin.*(fail|error)|Shadow|was not located|[Ii]njection|MixinTransformerError|InvalidMixin|Failed to load feature|Crash Report|Caused by|Exception in|ERROR\]" run.log | head -200
echo "===== tail"
tail -40 run.log
grep -q "NOAMM_MIXIN_AUDIT_DONE" run.log
