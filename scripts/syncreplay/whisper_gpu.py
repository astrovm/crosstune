"""What a voice sings and when, as `syncreplay.py` asks for it with WHISPER_PYTHON: Whisper medium on a GPU,
run in that Python as it needs PyTorch for the GPU, with the same settings as faster-whisper on the CPU.
Prints one [[word, seconds], ...] per hearing.

    python whisper_gpu.py VOCALS.wav LANGUAGE
"""
import json
import sys

import torch
import whisper

# Whisper retries a stretch it can't make out by sampling at random, so two hearings can differ: see `agreed`.
HEARINGS = 2


def main():
    vocals, language = sys.argv[1:3]
    model = whisper.load_model("medium", device="cuda")
    sound = whisper.load_audio(vocals)
    hearings = []
    for seed in range(HEARINGS):
        torch.manual_seed(seed)
        # Faster-whisper's own defaults for what isn't set in `syncreplay.py`.
        heard = model.transcribe(sound, language=language, word_timestamps=True, condition_on_previous_text=False,
                                 beam_size=5, temperature=(0.0, 0.2, 0.4, 0.6, 0.8, 1.0), fp16=True, verbose=None)
        hearings.append([[word["word"], word["start"]] for segment in heard["segments"] for word in segment.get("words", [])])
    print(json.dumps(hearings))


if __name__ == "__main__":
    main()
