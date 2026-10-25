// The bridge from Kotlin to whisper.cpp: hears the words sung in a stretch of sound, and when each starts.
#include <jni.h>
#include <string>
#include <whisper.h>

namespace {
whisper_context* context(jlong instance) { return reinterpret_cast<whisper_context*>(instance); }

// Nothing is printed: Android has no console for it.
void quiet(ggml_log_level, const char*, void*) {}
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_astrovm_crosstune_NativeWhisper_nativeOpen(JNIEnv* env, jobject, jstring model) {
    whisper_log_set(quiet, nullptr);
    const char* path = env->GetStringUTFChars(model, nullptr);
    if (path == nullptr) return 0;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    // When each word starts, from where the model listens as it writes it; flash attention can't say.
    params.flash_attn = false;
    params.dtw_token_timestamps = true;
    params.dtw_aheads_preset = WHISPER_AHEADS_TINY;
    whisper_context* instance = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model, path);
    return reinterpret_cast<jlong>(instance);
}

// The words heard in [samples], 16 kHz mono, one a line: when it starts in milliseconds, a tab, and the word.
// As bytes, since a token can end partway through a letter, which Java's strings won't take.
JNIEXPORT jbyteArray JNICALL Java_com_astrovm_crosstune_NativeWhisper_nativeWords(JNIEnv* env, jobject, jlong instance, jfloatArray samples, jstring language, jint threads) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.beam_search.beam_size = 5;
    params.n_threads = threads;
    params.no_context = true;
    params.token_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    const char* lang = language == nullptr ? nullptr : env->GetStringUTFChars(language, nullptr);
    params.language = lang == nullptr ? "auto" : lang;
    jsize count = env->GetArrayLength(samples);
    jfloat* sound = env->GetFloatArrayElements(samples, nullptr);
    std::string words;
    if (sound != nullptr && whisper_full(context(instance), params, sound, count) == 0) {
        whisper_context* ctx = context(instance);
        whisper_token last = whisper_token_eot(ctx);
        for (int segment = 0; segment < whisper_full_n_segments(ctx); segment++) {
            for (int token = 0; token < whisper_full_n_tokens(ctx, segment); token++) {
                whisper_token_data data = whisper_full_get_token_data(ctx, segment, token);
                // Timestamps, the start and end marks, and the like aren't words.
                if (data.id >= last) continue;
                const char* text = whisper_full_get_token_text(ctx, segment, token);
                // A token starting with a space starts a word; the rest go on with the one before.
                bool starts = text[0] == ' ' || words.empty();
                if (starts) {
                    if (!words.empty()) words += '\n';
                    words += std::to_string(data.t_dtw < 0 ? -1 : data.t_dtw * 10);
                    words += '\t';
                }
                words += text;
            }
        }
    }
    if (sound != nullptr) env->ReleaseFloatArrayElements(samples, sound, JNI_ABORT);
    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(words.size()));
    if (out != nullptr) env->SetByteArrayRegion(out, 0, static_cast<jsize>(words.size()), reinterpret_cast<const jbyte*>(words.data()));
    return out;
}

JNIEXPORT void JNICALL Java_com_astrovm_crosstune_NativeWhisper_nativeClose(JNIEnv*, jobject, jlong instance) {
    whisper_free(context(instance));
}

}
