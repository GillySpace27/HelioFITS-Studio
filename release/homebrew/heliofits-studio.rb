cask "heliofits-studio" do
  version "0.9.2"

  on_arm do
    sha256 "84bf6bdfa3c2b6ce15c90536a090078416f4176eb5a7eb9bcc03a075823edefb"
    url "https://github.com/GillySpace27/HelioFITS-Studio/releases/download/v#{version}/HFStudio-#{version}.dmg"
  end
  on_intel do
    sha256 "02177aac67d43b3b3e9d8eb79787fad27761e6d6193e5712e7bb4b0508c16a1a"
    url "https://github.com/GillySpace27/HelioFITS-Studio/releases/download/v#{version}/HFStudio-#{version}-intel.dmg"
  end

  name "HelioFITS Studio"
  desc "Viewer for solar and heliospheric FITS and JPEG 2000 data"
  homepage "https://gilly.space/heliofits-studio/"

  # Every release so far is a GitHub pre-release, which brew's github_releases strategy skips, and the
  # repository's tags include old JHelioviewer ones (v2.15.7) that outrank 0.9.x. The releases feed
  # lists only real releases, pre-releases included.
  livecheck do
    url "https://github.com/GillySpace27/HelioFITS-Studio/releases.atom"
    regex(%r{releases/tag/v?(\d+\.\d+\.\d+)"}i)
    strategy :page_match
  end

  # The app's Python extensions are built for macOS 14 (otool minos on the 0.9.2 dmgs).
  depends_on macos: ">= :sonoma"

  app "HelioFITS Studio.app"

  zap trash: "~/HFStudio"
end
