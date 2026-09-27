// OCRSCAN: stub backend used for ABIs where the vendored ncnn / opencv-mobile
// static libraries are not present (this fork ships arm64-v8a only). Every
// entry point reports "unavailable" so the Kotlin layer can show a clear error
// instead of crashing on a missing native symbol.
#include <jni.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_fcitx_fcitx5_android_input_ocr_PpOcrV5Native_nativeAvailable(JNIEnv*, jclass)
{
    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_input_ocr_PpOcrV5Native_nativeInit(
    JNIEnv*, jclass, jstring, jstring, jstring, jstring, jint, jboolean, jint)
{
    return 0L;
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_ocr_PpOcrV5Native_nativeRelease(JNIEnv*, jclass, jlong)
{
}

JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_input_ocr_PpOcrV5Native_nativeRecognize(
    JNIEnv* env, jclass, jlong, jobject, jstring)
{
    return env->NewStringUTF("");
}

} // extern "C"
