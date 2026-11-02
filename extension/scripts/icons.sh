#!/bin/sh
# Draws the extension's icons from the app's own store icon, as rounded squares. Run again when it
# changes; needs ImageMagick. The 128 one keeps a transparent edge, as the Chrome Web Store asks.
#   sh scripts/icons.sh
set -eu
cd "$(dirname "$0")/.."
source=../fastlane/metadata/android/en-US/images/icon.png
# The middle of the icon, so the note isn't lost at 16 pixels, with its corners rounded.
rounded() {
  magick "$source" -gravity center -crop 384x384+0+0 +repage \
    \( -size 384x384 xc:none -fill white -draw "roundrectangle 0,0 383,383 84,84" \) \
    -compose CopyOpacity -composite -resize "$1x$1" PNG32:"$2"
}
for size in 16 32 48; do rounded "$size" "icons/icon-$size.png"; done
rounded 112 /tmp/crosstune-icon-112.png
magick /tmp/crosstune-icon-112.png -background none -gravity center -extent 128x128 PNG32:icons/icon-128.png
rm /tmp/crosstune-icon-112.png
