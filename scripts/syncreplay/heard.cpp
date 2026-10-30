// Same as app/src/main/cpp/sungwords.cpp, on the computer: f32le 16 kHz mono on stdin, words out.
#include "whisper.h"
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
static void quiet(ggml_log_level, const char*, void*) {}
int main(int argc, char** argv) {
    whisper_log_set(quiet, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false; cp.flash_attn = false; cp.dtw_token_timestamps = true; cp.dtw_aheads_preset = getenv("BASE") ? WHISPER_AHEADS_BASE : WHISPER_AHEADS_TINY;
    whisper_context* ctx = whisper_init_from_file_with_params(argv[1], cp);
    std::vector<float> s; float f; while (fread(&f, 4, 1, stdin) == 1) s.push_back(f);
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.beam_search.beam_size = 5; p.n_threads = 4; p.no_context = true; p.token_timestamps = true;
    p.print_progress = p.print_realtime = p.print_special = p.print_timestamps = false;
    p.language = argc > 2 ? argv[2] : "auto";
    if (argc > 3 && argv[3][0]) p.initial_prompt = argv[3];
    std::string words;
    if (whisper_full(ctx, p, s.data(), s.size()) == 0) {
        whisper_token last = whisper_token_eot(ctx);
        for (int g = 0; g < whisper_full_n_segments(ctx); g++) for (int t = 0; t < whisper_full_n_tokens(ctx, g); t++) {
            whisper_token_data d = whisper_full_get_token_data(ctx, g, t);
            if (d.id >= last) continue;
            const char* text = whisper_full_get_token_text(ctx, g, t);
            bool starts = text[0] == ' ' || words.empty();
            if (starts) { if (!words.empty()) words += '\n'; words += std::to_string(d.t_dtw < 0 ? -1 : d.t_dtw * 10); words += '\t'; }
            words += text;
        }
    }
    fwrite(words.data(), 1, words.size(), stdout); puts("");
    whisper_free(ctx);
}
