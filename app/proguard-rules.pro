# MusicService names are stored in preferences and name the per-service activity aliases in the
# manifest (see LinkInterception), so they must survive minification unchanged.
-keepclassmembernames enum com.astrovm.crosstune.MusicService { <fields>; }
