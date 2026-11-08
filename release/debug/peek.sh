# Debug-build target: HelioFITS Peek (GillySpace27/HelioFITS-Peek). Sourced by debug-build.sh.
# NOT FULLY WORKED OUT: Peek is a sandboxed app with Quick Look and thumbnail extensions that share settings
# through an app group. An ad-hoc debug copy gets none of that isolation, and running it registers its extensions
# with LaunchServices (RELEASING.md: the recurring "generic icon" thumbnail bug). So this target builds the Debug
# configuration ad hoc and launches the app, and cleaning up is yours: quit it, then run ./lsclean.sh in the clone.
TARGET_REPO=GillySpace27/HelioFITS-Peek
TARGET_NAME="HelioFITS Peek"
TARGET_DATA="${HFS_DEBUG_PEEK_HOME:-$HOME/HelioFITS-Peek-debug}"   # unused by the app; kept for the report
TARGET_ENV=""
TARGET_ARGS=""
TARGET_READY=""

target_build() {
    local clone=$1 out=$2 dd
    dd="$out/derived"; mkdir -p "$out"
    ( cd "$clone" && xcodebuild build -project HelioFITS.xcodeproj -scheme HelioFITS -configuration Debug \
        -destination 'platform=macOS,arch=arm64' -derivedDataPath "$dd" \
        DEVELOPMENT_TEAM=UB45PPC2JS CODE_SIGN_IDENTITY="-" CODE_SIGN_STYLE=Manual AD_HOC_CODE_SIGNING_ALLOWED=YES ) || return 1
    rm -rf "$out/$TARGET_NAME.app"
    ditto "$dd/Build/Products/Debug/$TARGET_NAME.app" "$out/$TARGET_NAME.app"
}
