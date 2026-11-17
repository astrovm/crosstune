// A decoder with deterministic outcomes, linked to the production JNI bridge on the host.
#include <whisper.h>

struct whisper_context { int outcome; };

extern "C" {
void whisper_log_set(ggml_log_callback, void*) {}
whisper_context_params whisper_context_default_params() { return {}; }
whisper_context* whisper_init_from_file_with_params(const char*, whisper_context_params) { return new whisper_context{}; }
void whisper_free(whisper_context* ctx) { delete ctx; }
whisper_full_params whisper_full_default_params(whisper_sampling_strategy) { return {}; }
int whisper_full(whisper_context* ctx, whisper_full_params, const float* samples, int count) {
    ctx->outcome = count > 0 ? static_cast<int>(samples[0]) : 0;
    return ctx->outcome == 1 ? 0 : ctx->outcome;
}
whisper_token whisper_token_eot(whisper_context*) { return 100; }
int whisper_full_n_segments(whisper_context* ctx) { return ctx->outcome == 1 ? 1 : 0; }
int whisper_full_n_tokens(whisper_context*, int) { return 1; }
whisper_token_data whisper_full_get_token_data(whisper_context*, int, int) {
    whisper_token_data data{};
    data.id = 1;
    data.t_dtw = 12;
    return data;
}
const char* whisper_full_get_token_text(whisper_context*, int, int) { return " hello"; }
}
