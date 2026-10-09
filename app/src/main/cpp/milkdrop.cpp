// The bridge from Kotlin to projectM: each call runs on the GL thread, with its context current.
#include <jni.h>
#include <projectM-4/projectM.h>

namespace {
projectm_handle handle(jlong instance) { return reinterpret_cast<projectm_handle>(instance); }
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeCreate(JNIEnv*, jobject, jint width, jint height) {
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
    return reinterpret_cast<jlong>(instance);
}

JNIEXPORT void JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeResize(JNIEnv*, jobject, jlong instance, jint width, jint height) {
    projectm_set_window_size(handle(instance), width, height);
}

JNIEXPORT void JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeLoad(JNIEnv* env, jobject, jlong instance, jstring preset, jboolean smooth) {
    const char* data = env->GetStringUTFChars(preset, nullptr);
    if (data == nullptr) return;
    projectm_load_preset_data(handle(instance), data, smooth);
    env->ReleaseStringUTFChars(preset, data);
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

JNIEXPORT void JNICALL Java_com_astrovm_crosstune_NativeMilkdrop_nativeDestroy(JNIEnv*, jobject, jlong instance) {
    projectm_destroy(handle(instance));
}

}
