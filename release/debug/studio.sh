# Debug-build target: HelioFITS Studio. Sourced by release/debug-build.sh; defines what is specific to this app.
# A new target (Peek, pyHelioFITS) is one file like this: set the four variables, define target_build.
TARGET_REPO=GillySpace27/HelioFITS-Studio
TARGET_NAME="HelioFITS Studio"
TARGET_DATA="${HFS_DEBUG_HOME:-$HOME/HFStudio-debug}"   # survives rebuilds; the app bundle does not
TARGET_ENV="HFS_HOME=$TARGET_DATA"                      # Directories.homeOverride reads this
TARGET_READY='ls "$TARGET_DATA"/Logs/*.log'   # evidence the app came up against its data folder

# target_build <clone> <outdir> <tag>: leave "$outdir/$TARGET_NAME.app" built from the clone's checked-out tree.
target_build() {
    local clone=$1 out=$2 tag=$3 ver dmg mnt
    ver=$(tr -d '[:space:]' < "$clone/VERSION")
    rm -f "$clone/release/HFStudio-$ver.dmg"
    # The build smoke-launches the app: give it a throwaway home so it cannot write into the real ~/HFStudio.
    ( cd "$clone" && HFS_CHANNEL=debug HFS_UNSIGNED=1 HFS_HOME="$clone/.smoke-home" release/deploy_release.sh notarize ) || return 1
    rm -rf "$clone/.smoke-home"
    dmg="$clone/release/HFStudio-$ver.dmg"
    [ -f "$dmg" ] || { echo "!! no dmg at $dmg" >&2; return 1; }
    cp "$dmg" "$HOME/hfs-rc/HFStudio-$ver-debug-$tag.dmg"
    mnt=$(mktemp -d)
    hdiutil attach -quiet -nobrowse -readonly -mountpoint "$mnt" "$dmg" || return 1
    rm -rf "$out/$TARGET_NAME.app"; mkdir -p "$out"
    ditto "$mnt/$TARGET_NAME.app" "$out/$TARGET_NAME.app"
    hdiutil detach -quiet "$mnt"; rmdir "$mnt"
}
