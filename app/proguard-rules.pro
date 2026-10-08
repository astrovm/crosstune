# MusicService names are stored in preferences and name the per-service activity aliases in the
# manifest (see LinkInterception), so they must survive minification unchanged.
-keepclassmembernames enum com.astrovm.crosstune.MusicService { <fields>; }

# Kuromoji finds its dictionary files next to its own classes, by their package, so they keep their names.
-keep class com.atilika.kuromoji.** { *; }
