// OCRSCAN: JNI bridge for the on-device PP-OCRv5 (ncnn) text recognizer.
//
// The Kotlin side holds a long "handle" to a heap-allocated PPOCRv5 instance so
// re-running recognition does not reload the (multi-MB) model every call. Bitmap
// pixels are locked with the NDK bitmap API and converted to an RGB cv::Mat; the
// model expects RGB order, not the ARGB_8888 the Android side hands us.
#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "ppocrv5.h"
#include "ppocrv5_dict.h"
#include "ppocrv5_preprocess.h"

// opencv-mobile 2.4.13.7 (v36) was compiled with a newer NDK whose libomp emits
// __kmpc_dispatch_deinit; the static libomp shipped in NDK r28 (LLVM 19)
// predates that entry point, so linking -fopenmp fails with an undefined symbol.
// Older libomps never performed the per-region deinit either -- the per-thread
// dispatch buffer is reused and released at thread exit -- so a no-op exactly
// restores the old, battle-tested behaviour. Weak so a future NDK that does
// export the real symbol wins.
extern "C" __attribute__((weak)) void __kmpc_dispatch_deinit(void*, int) {}

// Sort boxes into reading order: top-to-bottom, then left-to-right within a row.
static void sort_reading_order(std::vector<Object>& objects)
{
    std::sort(objects.begin(), objects.end(), [](const Object& a, const Object& b) {
        const float ay = a.rrect.center.y;
        const float by = b.rrect.center.y;
        const float band = std::max(a.rrect.size.height, b.rrect.size.height) * 0.5f;
        if (std::abs(ay - by) > band)
            return ay < by;
        return a.rrect.center.x < b.rrect.center.x;
    });
}

static std::string objects_to_text(const std::vector<Object>& objects)
{
    std::string out;
    for (size_t i = 0; i < objects.size(); ++i)
    {
        const Object& obj = objects[i];
        if (obj.text.empty())
            continue;
        if (obj.orientation == 0)
        {
            for (size_t j = 0; j < obj.text.size(); ++j)
            {
                const Character& ch = obj.text[j];
                if (ch.id >= 0 && ch.id < character_dict_size)
                    out += character_dict[ch.id];
            }
        }
        else
        {
            // vertical text: stack glyphs, one per line
            for (size_t j = 0; j < obj.text.size(); ++j)
            {
                const Character& ch = obj.text[j];
                if (ch.id >= 0 && ch.id < character_dict_size)
                {
                    out += character_dict[ch.id];
                    out += '\n';
                }
            }
        }
        if (!out.empty() && out.back() != '\n')
            out += '\n';
    }
    // trailing newline trimmed; caller handles surrounding whitespace.
    while (!out.empty() && out.back() == '\n')
        out.pop_back();
    return out;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_fcitx_fcitx5_android_input_ocr_PpOcrV5Native_nativeAvailable(JNIEnv*, jclass)
{
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_input_ocr_PpOcrV5Native_nativeInit(
    JNIEnv* env, jclass, jstring detParam, jstring detBin,
    jstring recParam, jstring recBin, jint numThreads, jboolean useFp16, jint targetSize)
{
    const char* dp = env->GetStringUTFChars(detParam, nullptr);
    const char* db = env->GetStringUTFChars(detBin, nullptr);
    const char* rp = env->GetStringUTFChars(recParam, nullptr);
    const char* rb = env->GetStringUTFChars(recBin, nullptr);

    PPOCRv5* engine = nullptr;
    try
    {
        engine = new PPOCRv5;
        engine->load(dp, db, rp, rb, useFp16 == JNI_TRUE, false);
        if (targetSize > 0)
            engine->set_target_size(static_cast<int>(targetSize));
        // numThreads drives the DET network's internal ncnn thread pool (see
        // PPOCRv5::numThreads / detect()).
        if (numThreads > 0)
            engine->numThreads = static_cast<int>(numThreads);
    }
    catch (...)
    {
        delete engine;
        engine = nullptr;
    }

    env->ReleaseStringUTFChars(detParam, dp);
    env->ReleaseStringUTFChars(detBin, db);
    env->ReleaseStringUTFChars(recParam, rp);
    env->ReleaseStringUTFChars(recBin, rb);

    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_ocr_PpOcrV5Native_nativeRelease(JNIEnv*, jclass, jlong handle)
{
    delete reinterpret_cast<PPOCRv5*>(handle);
}

JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_input_ocr_PpOcrV5Native_nativeRecognize(
    JNIEnv* env, jclass, jlong handle, jobject bitmap, jstring preprocessSpec)
{
    PPOCRv5* engine = reinterpret_cast<PPOCRv5*>(handle);
    if (engine == nullptr)
        return env->NewStringUTF("");

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return env->NewStringUTF("");

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return env->NewStringUTF("");

    cv::Mat rgb;
    std::string result;
    try
    {
        if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888)
        {
            cv::Mat rgba(info.height, info.width, CV_8UC4, pixels);
            cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
        }
        else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565)
        {
            cv::Mat c565(info.height, info.width, CV_16UC1, pixels);
            cv::Mat bgr;
            cv::cvtColor(c565, bgr, cv::COLOR_BGR5652RGB);
            rgb = bgr;
        }
        else
        {
            // Unsupported format: bail without touching rgb.
            rgb = cv::Mat();
        }

        if (!rgb.empty())
        {
            const char* spec = preprocessSpec ? env->GetStringUTFChars(preprocessSpec, nullptr) : nullptr;
            const unsigned int flags = ppocrv5_preprocess_flags(spec, PPOCRV5_PREPROCESS_DEFAULT);
            if (spec)
                env->ReleaseStringUTFChars(preprocessSpec, spec);

            ppocrv5_preprocess(rgb, flags);

            std::vector<Object> objects;
            engine->detect_and_recognize(rgb, objects);
            sort_reading_order(objects);
            result = objects_to_text(objects);
        }
    }
    catch (...)
    {
        result.clear();
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
