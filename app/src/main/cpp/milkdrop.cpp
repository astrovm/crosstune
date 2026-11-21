// The bridge from Kotlin to projectM: each call runs on the GL thread, with its context current.
#include <android/native_window_jni.h>
#include <jni.h>
#include <projectM-4/projectM.h>

namespace {
projectm_handle handle(jlong instance) { return reinterpret_cast<projectm_handle>(instance); }

// projectM says so when a preset won't load, e.g. a shader this phone can't compile.
void failed(const char*, const char*, void* didFail) { *static_cast<bool*>(didFail) = true; }
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeCreate(JNIEnv* env, jobject, jint width, jint height, jstring textures) {
    projectm_handle instance = projectm_create();
    if (instance == nullptr) return 0;
    projectm_set_window_size(instance, width, height);
    // A coarse mesh is plenty on a phone, and much lighter.
    projectm_set_mesh_size(instance, 32, 24);
    projectm_set_fps(instance, 30);
    // Kotlin picks when the preset changes; projectM never changes it on its own.
    projectm_set_preset_locked(instance, true);
    projectm_set_hard_cut_enabled(instance, false);
    projectm_set_soft_cut_duration(instance, 3.0);
    // Where the images presets draw with were copied to; without them, those presets draw them blank.
    if (textures != nullptr) {
        const char* path = env->GetStringUTFChars(textures, nullptr);
        if (path != nullptr) {
            projectm_set_texture_search_paths(instance, &path, 1);
            env->ReleaseStringUTFChars(textures, path);
        }
    }
    return reinterpret_cast<jlong>(instance);
}

JNIEXPORT void JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeResize(JNIEnv*, jobject, jlong instance, jint width, jint height) {
    projectm_set_window_size(handle(instance), width, height);
}

JNIEXPORT jboolean JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeLoad(JNIEnv* env, jobject, jlong instance, jstring preset, jboolean smooth) {
    const char* data = env->GetStringUTFChars(preset, nullptr);
    if (data == nullptr) return JNI_FALSE;
    // Loading happens right here, so whether it failed is known once it returns.
    bool didFail = false;
    projectm_set_preset_switch_failed_event_callback(handle(instance), failed, &didFail);
    projectm_load_preset_data(handle(instance), data, smooth);
    projectm_set_preset_switch_failed_event_callback(handle(instance), nullptr, nullptr);
    env->ReleaseStringUTFChars(preset, data);
    return didFail ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeHear(JNIEnv* env, jobject, jlong instance, jbyteArray samples, jint count) {
    jbyte* bytes = env->GetByteArrayElements(samples, nullptr);
    if (bytes == nullptr) return;
    projectm_pcm_add_uint8(handle(instance), reinterpret_cast<const uint8_t*>(bytes), static_cast<unsigned int>(count), PROJECTM_MONO);
    env->ReleaseByteArrayElements(samples, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeDraw(JNIEnv*, jobject, jlong instance) {
    projectm_opengl_render_frame(handle(instance));
}

// The size of the buffers drawn into [surface] from the next one on, which Android stretches to fill
// the window. Set by the thread that draws, so a new size comes with the first frame drawn at it.
// The format stays the one EGL set for its config: 0 would put back the window's own.
JNIEXPORT jboolean JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeBuffers(JNIEnv* env, jclass, jobject surface, jint width, jint height) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return JNI_FALSE;
    int32_t format = ANativeWindow_getFormat(window);
    int32_t failed = format < 0 ? format : ANativeWindow_setBuffersGeometry(window, width, height, format);
    ANativeWindow_release(window);
    return failed == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeDestroy(JNIEnv*, jobject, jlong instance) {
    projectm_destroy(handle(instance));
}

}
