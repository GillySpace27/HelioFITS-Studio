#!/bin/bash
# Build from source, then run: clicking this always launches the newest code in
# the demo-all worktree, not a snapshot taken whenever someone last packaged it.
# ant's run target depends on jar, which depends on compile, so one call does it.
SRC="$HOME/Documents/NWRA/PUNCH_Science/jhv-demo"
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
LOG="$HOME/Library/Logs/heliofits-studio-launch.log"
mkdir -p "$(dirname "$LOG")"

# Already open? Bring it forward rather than building a second copy: the Dock
# cannot tell that this wrapper and the JVM it starts are the same program, so
# without this every click would spawn another Studio.
if pgrep -f "HFStudio.jar" > /dev/null; then
    osascript -e 'tell application "System Events" to set frontmost of (first process whose unix id is (do shell script "pgrep -f HFStudio.jar | head -1") as integer) to true' 2>/dev/null
    exit 0
fi

cd "$SRC" || { osascript -e 'display alert "HelioFITS Studio" message "Source tree not found at ~/Documents/NWRA/PUNCH_Science/jhv-demo"'; exit 1; }
if ! /opt/homebrew/bin/ant run > "$LOG" 2>&1; then
    osascript -e "display alert \"HelioFITS Studio failed to build\" message \"See $LOG\""
    exit 1
fi
