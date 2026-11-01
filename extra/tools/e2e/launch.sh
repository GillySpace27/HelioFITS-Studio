#!/bin/sh
# usage: E2E_OUT=<dir> launch.sh <worktree> <name>   (throwaway home; never the installed app)
WT=$1; NAME=$2; D=${E2E_OUT:-/tmp/hfs-e2e-out}
mkdir -p "$D"
H=$(mktemp -d /tmp/hfs-e2e-$NAME-XXXXXX)
echo "$H" > "$D/$NAME.home"
mkdir -p "$H/HFStudio/Settings" && echo "dataSources.defaultServer=GSFC" > "$H/HFStudio/Settings/user.properties"
cd "$WT" || exit 1
JAVA_TOOL_OPTIONS="-Duser.home=$H" nohup java --enable-native-access=ALL-UNNAMED $HFS_JVM_EXTRA -jar HFStudio.jar > "$D/$NAME.log" 2>&1 &
echo $! > "$D/$NAME.pid"
