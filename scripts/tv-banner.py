"""Writes the TV banner: the launcher logo on its violet, and the app's name beside it, as vector paths.

python3 scripts/tv-banner.py Roboto.ttf app/src/main/res/drawable/ic_launcher_foreground.xml > app/src/main/res/drawable/tv_banner.xml
"""
import sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.transformPen import TransformPen
font = instantiateVariableFont(TTFont(sys.argv[1]), {"wght": 700, "wdth": 100})
glyphs, cmap = font.getGlyphSet(), font.getBestCmap()
upm = font["head"].unitsPerEm
text = "Crosstune"
size = 40.0  # dp, the name's height in the 320x180 banner
scale = size / upm
# Lay the glyphs out, then place the name so logo and name sit centered together.
pen = SVGPathPen(glyphs); bounds = BoundsPen(glyphs)
x = 0
hmtx = font["hmtx"]
for ch in text:
    name = cmap[ord(ch)]
    t = (scale, 0, 0, -scale, x * scale, 0)
    glyphs[name].draw(TransformPen(pen, t)); glyphs[name].draw(TransformPen(bounds, t))
    x += hmtx[name][0]
xmin, ymin, xmax, ymax = bounds.bounds
logo = 96.0  # the 108 viewport's foreground drawn this big
gap = 10.0
s = logo / 108
# The logo's drawing spans x 31..78 and y 32..77 of its 108.
width = 47 * s + gap + (xmax - xmin)
left = (320 - width) / 2
logo_x = left - 31 * s
name_x = left + 47 * s + gap - xmin
name_y = 90 - (ymin + ymax) / 2
fg = open(sys.argv[2]).read()
inner = fg.split('android:viewportHeight="108">', 1)[1].rsplit("</vector>", 1)[0]
print(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- The TV home screen's tile: the launcher logo and the app's name, made by scripts/tv-banner.py from Roboto Bold. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="320dp"
    android:height="180dp"
    android:viewportWidth="320"
    android:viewportHeight="180">
    <path
        android:fillColor="#FF4F2BD9"
        android:pathData="M0,0h320v180h-320z" />
    <group
        android:translateX="{logo_x:.2f}"
        android:translateY="{90 - 54.5 * s:.2f}"
        android:scaleX="{logo / 108:.4f}"
        android:scaleY="{logo / 108:.4f}">{inner.rstrip()}
    </group>
    <group
        android:translateX="{name_x:.2f}"
        android:translateY="{name_y:.2f}">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="{pen.getCommands()}" />
    </group>
</vector>''')
