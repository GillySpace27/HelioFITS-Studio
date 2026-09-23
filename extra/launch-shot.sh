#!/bin/bash
# Start the application, wait for its renderer, screenshot its window, stop it. Used by
# .github/workflows/launch.yml (the launchers in the zip) and package.yml (the packaged builds).
#
#   extra/launch-shot.sh <name> <command...>
#
# Leaves shot-<name>.png, app-<name>.log, app-<name>.out and, if the app was still running when
# the screenshot was taken, alive-<name>.txt, for extra/launch-judge.sh and launch-pixels.py.
# On Linux run it inside xvfb-run. On Windows it runs under Git Bash and uses PowerShell only to
# capture the window. On macOS it captures the whole screen with screencapture.
set -u
NAME="$1"; shift
WAIT_FOR_GL="${WAIT_FOR_GL:-180}"   # seconds to wait for a graphics context
SETTLE="${SETTLE:-25}"              # seconds after that for the image to load and draw

# A fresh home, so no earlier session is restored and the log is this run's alone.
# JAVA_TOOL_OPTIONS reaches the JVM whether it was started by a script or a native launcher.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) WINDOWS=1; HOME_DIR="$(cygpath -w "$RUNNER_TEMP")\\home-$NAME"; LOGS="$(cygpath -u "$HOME_DIR")/HFStudio/Logs" ;;
    *)                    WINDOWS=0; HOME_DIR="$RUNNER_TEMP/home-$NAME";                 LOGS="$HOME_DIR/HFStudio/Logs" ;;
esac
mkdir -p "$LOGS/.."
export JAVA_TOOL_OPTIONS="-Duser.home=$HOME_DIR"

"$@" > "app-$NAME.out" 2>&1 &
APP=$!
for _ in $(seq 1 "$WAIT_FOR_GL"); do
    grep -qs "OpenGL context" "$LOGS"/*.log && break
    kill -0 "$APP" 2>/dev/null || break
    sleep 1
done
sleep "$SETTLE"

if [ "$WINDOWS" = 1 ]; then
    # The app window only, found by its title: the desktop behind it (wallpaper, icons) measured
    # 6592 warm pixels, enough to pass the pixel check with nothing drawn at all.
    powershell -NoProfile -Command '
      Add-Type -AssemblyName System.Drawing
      Add-Type "using System; using System.Runtime.InteropServices;
        public class W { [StructLayout(LayoutKind.Sequential)] public struct R { public int L, T, Rt, B; }
          [DllImport(""user32.dll"")] public static extern bool GetWindowRect(IntPtr h, out R r); }"
      $p = Get-Process | Where-Object { $_.MainWindowTitle -like "*HelioFITS Studio*" } | Select-Object -First 1
      if (-not $p) { "no HelioFITS Studio window found"; exit 1 }
      $r = New-Object W+R; [W]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
      $w = $r.Rt - $r.L; $h = $r.B - $r.T; "window of $($p.ProcessName): ${w}x${h} at $($r.L),$($r.T)"
      $bmp = New-Object System.Drawing.Bitmap $w, $h
      [System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
      $bmp.Save("shot-'"$NAME"'.png")' || true
elif [ "$(uname -s)" = Darwin ]; then
    # The whole screen: a runner's desktop is whatever it is, so launch-pixels.py's verdict here
    # is only as good as that background is plain. Look at the picture.
    screencapture -x "shot-$NAME.png" || true
else
    # Xvfb's root window is black and the app's chrome is purple and grey, so the whole screen
    # is fair to count: 8529 drawn pixels against 162 from the interface on the first run.
    import -window root "shot-$NAME.png" || true
fi

if kill -0 "$APP" 2>/dev/null; then
    echo alive > "alive-$NAME.txt"
    if [ "$WINDOWS" = 1 ]; then
        taskkill //F //T //PID "$(cat /proc/$APP/winpid)" > /dev/null 2>&1 || true
    else
        # SIGTERM runs the app's shutdown hooks, which take a few seconds; then insist.
        kill "$APP"
        for _ in $(seq 1 10); do kill -0 "$APP" 2>/dev/null || break; sleep 1; done
        kill -9 "$APP" 2>/dev/null || true
    fi
fi
cp "$LOGS"/*.log "app-$NAME.log" 2>/dev/null || true
exit 0
