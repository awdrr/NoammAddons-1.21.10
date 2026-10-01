#!/usr/bin/env bash
# Temporary 1.21.11 porting aid: prints class signatures from the mapped Minecraft jar
# and a compile-error summary into the CI log. Remove once the port builds.
set +e
echo "===== parchment versions"
curl -s https://maven.parchmentmc.org/org/parchmentmc/data/parchment-1.21.11/maven-metadata.xml | grep -o '<version>[^<]*</version>' | tail -3
JAR=""
for j in $(find ~/.gradle/caches/fabric-loom .gradle/loom-cache -name '*.jar' 2>/dev/null | grep -v sources); do
  if unzip -l "$j" 2>/dev/null | grep -q 'net/minecraft/client/Minecraft.class'; then JAR="$j"; break; fi
done
echo "===== named jar: $JAR"
FAPI=$(find ~/.gradle/caches -name 'fabric-api-*0.141.6*.jar' 2>/dev/null | head -1)
while read -r line; do
  [ -z "$line" ] && continue
  case "$line" in
    pkg:*) echo "===== package ${line#pkg:}"; unzip -l "$JAR" | grep -o "${line#pkg:}[^ ]*\.class" | grep -v '\$' | head -80 ;;
    priv:*) spec="${line#priv:}"; cls="${spec%% *}"; pat="${spec#* }"; echo "===== (private) $cls ~ $pat"; javap -cp "$JAR" -p "$cls" 2>&1 | grep -E "$pat" | head -60 ;;
    grep:*) spec="${line#grep:}"; cls="${spec%% *}"; pat="${spec#* }"; echo "===== $cls ~ $pat"; javap -cp "$JAR" -public "$cls" 2>&1 | grep -E "$pat" | head -60 ;;
    *) echo "===== $line"; javap -cp "$JAR" -public "$line" 2>&1 | head -120 ;;
  esac
done < .github/port-probe.txt
echo "===== mixin check"
python3 .github/mixin-check.py "$JAR" src/main/java/com/github/noamm9/mixin
echo "===== compile errors"
grep -E '^e: |error:|: error|warning: .*(target|Cannot find|Unable to)|FAILURE|What went wrong' -A2 build.log | grep -v '^--$' | head -400
