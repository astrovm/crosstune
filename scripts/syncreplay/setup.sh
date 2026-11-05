#!/usr/bin/env bash
# Prep tools only. Requires uv, ffmpeg, yt-dlp, cmake and a C++ compiler on PATH.
# REPLAY_ROCM=1 installs the ROCm wheels on Linux x86_64; it does not configure the GPU driver.
set -euo pipefail
cache=${REPLAY_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/agent-cache/crosstune-syncreplay}
export UV_CACHE_DIR=${UV_CACHE_DIR:-$cache/downloads}
for tool in uv ffmpeg yt-dlp cmake; do
    command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }
done
if [[ ! -x $cache/venv/bin/python ]]; then
    uv venv --python 3.12 "$cache/venv"
fi
index=https://download.pytorch.org/whl/cpu
if [[ ${REPLAY_ROCM:-0} == 1 ]]; then
    [[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'ROCm needs Linux x86_64' >&2; exit 1; }
    index=https://download.pytorch.org/whl/rocm7.2
fi
uv pip install --python "$cache/venv/bin/python" torch==2.14.1 torchaudio==2.11.0 --index-url "$index"
uv pip install --python "$cache/venv/bin/python" demucs faster-whisper numpy soundfile torchcodec
if [[ ${REPLAY_ROCM:-0} == 1 ]]; then
    # Whisper's Linux dependency assumes NVIDIA. ROCm supplies the same module through triton-rocm.
    uv pip install --python "$cache/venv/bin/python" more-itertools numba tiktoken tqdm
    uv pip install --python "$cache/venv/bin/python" --no-deps openai-whisper
    uv pip uninstall --python "$cache/venv/bin/python" triton
    uv pip install --python "$cache/venv/bin/python" --reinstall triton-rocm==3.8.0 --index-url "$index"
else
    uv pip install --python "$cache/venv/bin/python" openai-whisper
fi
