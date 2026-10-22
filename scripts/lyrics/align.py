#!/usr/bin/env python3
"""Experiment with local vocal timing. Reports evidence; never edits the app's saved timings."""
import argparse
import difflib
import json
import re
import statistics
import unicodedata
import wave


def normalize(text):
    return ''.join(c.lower() for c in unicodedata.normalize('NFKC', text) if c.isalnum())


def observations(lines, segments):
    result = []
    for segment in segments:
        if segment['avg_logprob'] < -0.7 or segment['no_speech_prob'] > 0.2:
            continue
        text = normalize(segment['text'])
        scored = [(difflib.SequenceMatcher(None, normalize(line['text']), text).ratio(), line) for line in lines
                  if abs(line['start'] - segment['start']) <= 45]
        scored.sort(key=lambda pair: pair[0], reverse=True)
        if not scored or scored[0][0] < 0.65 or len(text) < 8:
            continue
        if len(scored) > 1 and scored[0][0] - scored[1][0] < 0.15:
            continue
        score, line = scored[0]
        result.append({'line': line['start'], 'heard': segment['start'], 'shift': segment['start'] - line['start'], 'score': round(score, 3)})
    return result


def correction(points):
    points = list({point['line']: point for point in points}.values())
    if len(points) < 3:
        return None
    shift = statistics.median(point['shift'] for point in points)
    agreed = [point for point in points if abs(point['shift'] - shift) <= 1.5]
    if len(agreed) < 3 or max(point['line'] for point in agreed) - min(point['line'] for point in agreed) < 8:
        return None
    return {'offset_seconds': round(shift, 3), 'matching_lines': len(agreed)}


def lrc_lines(text):
    lines = []
    for raw in text.splitlines():
        match = re.match(r'\[(\d+):(\d+(?:\.\d+)?)\](.*)', raw)
        if match and match[3].strip():
            lines.append({'start': int(match[1]) * 60 + float(match[2]), 'text': match[3].strip()})
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audio', help='16 kHz mono PCM WAV, kept on this machine')
    parser.add_argument('lyrics', help='LRC for the same song')
    parser.add_argument('--model', required=True, help='local faster-whisper model directory')
    parser.add_argument('--threads', type=int, default=4, help='CPU threads, default: 4')
    args = parser.parse_args()
    import numpy as np
    from faster_whisper import WhisperModel
    with wave.open(args.audio) as audio:
        if (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) != (16000, 1, 2):
            parser.error('audio must be mono 16-bit PCM WAV at 16 kHz')
        samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    model = WhisperModel(args.model, device='cpu', compute_type='int8', cpu_threads=args.threads, num_workers=1)
    segments, _ = model.transcribe(samples, language='ja', beam_size=3, word_timestamps=True, condition_on_previous_text=False)
    rows = [{'start': s.start, 'text': s.text, 'avg_logprob': s.avg_logprob, 'no_speech_prob': s.no_speech_prob} for s in segments]
    with open(args.lyrics, encoding='utf8') as file:
        points = observations(lrc_lines(file.read()), rows)
    print(json.dumps({'correction': correction(points), 'observations': points}, indent=2))


if __name__ == '__main__':
    main()
