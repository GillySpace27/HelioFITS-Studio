# Debug-build target: HelioFITS Studio. Sourced by release/debug-build.sh; defines what is specific to this app.
# A new target (Peek, pyHelioFITS) is one file like this: set the four variables, define target_build.
TARGET_REPO=GillySpace27/HelioFITS-Studio
TARGET_NAME="HelioFITS Studio"
TARGET_DATA="${HFS_DEBUG_HOME:-$HOME/HFStudio-debug}"   # survives rebuilds; the app bundle does not
TARGET_ENV="HFS_HOME=$TARGET_DATA"                      # Directories.homeOverride reads this

# target_build <clone> <outdir> <tag>: leave "$outdir/$TARGET_NAME.app" built from the clone's checked-out tree.
target_build() {
    local clone=$1 out=$2 tag=$3 ver dmg mnt
    ver=$(tr -d '[:space:]' < "$clone/VERSION")
    rm -f "$clone/release/HFStudio-$ver.dmg"
    ( cd "$clone" && HFS_UNSIGNED=1 release/deploy_release.sh notarize ) || return 1
    dmg="$clone/release/HFStudio-$ver.dmg"
    [ -f "$dmg" ] || { echo "!! no dmg at $dmg" >&2; return 1; }
    cp "$dmg" "$HOME/hfs-rc/HFStudio-$ver-debug-$tag.dmg"
    mnt=$(mktemp -d)
    hdiutil attach -quiet -nobrowse -readonly -mountpoint "$mnt" "$dmg" || return 1
    rm -rf "$out/$TARGET_NAME.app"; mkdir -p "$out"
    ditto "$mnt/$TARGET_NAME.app" "$out/$TARGET_NAME.app"
    hdiutil detach -quiet "$mnt"; rmdir "$mnt"
}
