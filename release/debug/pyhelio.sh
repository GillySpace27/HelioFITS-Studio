# Debug-build target: HelioFITS Cache Manager (the Mac app in GillySpace27/pyHelioFITS, app/). Sourced by debug-build.sh.
# Its settings are UserDefaults, so the debug copy gets its own bundle id (own settings) and no hfcore:// scheme
# (it must not take links from the real app). Its library is a folder of its own, passed on the command line.
TARGET_REPO=GillySpace27/pyHelioFITS
TARGET_NAME="HelioFITS Cache Manager"
TARGET_DATA="${HFS_DEBUG_PYHELIO_HOME:-$HOME/HelioFITS-debug}"
TARGET_ENV=""
_root="${HFS_DEBUG_ROOT:-$HOME/hfs-rc/debug}"
TARGET_ARGS="-corePath $_root/pyhelio-app/venv/bin/hfcore -libraryPath $TARGET_DATA/library"   # the core is the clone's own code
TARGET_READY=""

target_build() {
    local clone=$1 out=$2 plist
    mkdir -p "$out" "$TARGET_DATA/library"
    uv venv --quiet --allow-existing "$out/venv"
    uv pip install --quiet --python "$out/venv/bin/python" "$clone[archives]" || return 1
    ( cd "$clone/app" && tools/make_app.sh ) || return 1
    rm -rf "$out/$TARGET_NAME.app"; ditto "$clone/app/build/$TARGET_NAME.app" "$out/$TARGET_NAME.app"
    plist="$out/$TARGET_NAME.app/Contents/Info.plist"
    /usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier io.github.gillyspace27.heliad.debug" "$plist"
    /usr/libexec/PlistBuddy -c "Delete :CFBundleURLTypes" "$plist"
    codesign --force --deep --sign - "$out/$TARGET_NAME.app"
}
