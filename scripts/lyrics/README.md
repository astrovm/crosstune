# Vocal timing experiment

`align.py` compares existing LRC lines with Japanese vocals recognized by a local faster-whisper model. It prints candidate offset evidence, and never changes saved app timings. Use a local model directory so inference uploads no sound. Install the pinned `requirements.txt` in an isolated Python environment or container.

```sh
ffmpeg -i song.webm -ar 16000 -ac 1 song.wav
python align.py song.wav song.lrc --model /path/to/faster-whisper-small
python -m unittest discover -s scripts/lyrics
```

A correction needs three distinct matching lines spanning at least eight seconds. Low-confidence speech, likely silence, short fragments and ambiguous repeated lines supply no evidence. Timestamps from duplicate lyric sheets supply no evidence either. This experiment currently proposes constant offsets; it does not establish accurate timestamps for every word.

Tried against the official Plastic Love music video, whose duration matches the Samsung's 308.662-second YouTube Music session. The local small model recognized vocals around 40 seconds against LRCLIB's first timestamp at 30.49 seconds. It also hallucinated speech in the intro with low confidence, which is why confidence and matching the known words are required. Android's own local Japanese recognizer was tried on that singing and returned no usable words. It is not used for automatic app corrections.

The app instead uses its existing Shazam audio fingerprints to confirm a video's offset from the song, and remembers it per recording. This distinguishes intro offsets from lyric-provider timing problems; fingerprinting alone cannot repair arbitrary badly timed lines. Only fingerprints go to Shazam, never the audio. No song recordings or real lyric fixtures are committed here.
