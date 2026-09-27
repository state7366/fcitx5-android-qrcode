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

// Sort boxes into reading order: top-to-bottom by row, with left-to-right
// order inside a row. This is the conventional reading order the user expects
// (NOT left-most-start-first). Boxes whose vertical centres fall within a
// half-line band are treated as the same row and ordered by x.
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

// Newline-merge tuning: fractions of the image width treated as the right /
// left margin. A line that touches the right margin followed by a line starting
// at the left margin is treated as a wrapped continuation of the same line.
static constexpr float kMergeRightMarginFrac = 0.96f;
static constexpr float kMergeLeftMarginFrac  = 0.04f;

// Decide whether two consecutive text boxes should be joined into one logical
// line (i.e. NO newline between them). Returns true to merge.
//
// SCOPE: this newline-merge strategy is applied ONLY to on-device / local
// recognition models (PP-OCRv5 here, and analogously any other local engine).
// The 白描 / Baimiao cloud provider is intentionally EXCLUDED -- it returns
// server-formatted text that must not be re-flowed by this heuristic. Baimiao
// never passes through this native assembly, so the exclusion holds by
// construction.
//
// Two over-segmentation cases are collapsed:
//   1. Same physical row: the detector split one line into side-by-side boxes
//      (their vertical centres fall within a half-line band) -> join, no '\n'.
//   2. Wrapped continuation: the previous (horizontal) line reaches the right
//      margin of the image and the current line starts near the left margin,
//      i.e. the current line is the wrapped remainder of the same logical line
//      -> join, no '\n'. Vertical boxes are never merged into anything.
static bool should_merge_lines(const Object& prev, const Object& cur, int imgWidth)
{
    if (prev.orientation != 0 || cur.orientation != 0)
        return false; // never merge vertical text, and never merge into it

    cv::Point2f pa[4], pb[4];
    prev.rrect.points(pa);
    cur.rrect.points(pb);
    const float prevRight  = std::max({pa[0].x, pa[1].x, pa[2].x, pa[3].x});
    const float curLeft    = std::min({pb[0].x, pb[1].x, pb[2].x, pb[3].x});
    const float prevBottom = std::max({pa[0].y, pa[1].y, pa[2].y, pa[3].y});
    const float curTop     = std::min({pb[0].y, pb[1].y, pb[2].y, pb[3].y});

    // (1) same row: centres within a half-line band -> detector split one line.
    const float halfBand = std::max(prev.rrect.size.height, cur.rrect.size.height) * 0.5f;
    if (std::abs(prev.rrect.center.y - cur.rrect.center.y) <= halfBand)
        return true;

    // (2) wrapped continuation: prev runs to the right margin, cur resumes at
    // the left margin, and cur sits below prev.
    const float rightMargin = static_cast<float>(imgWidth) * kMergeRightMarginFrac;
    const float leftMargin  = static_cast<float>(imgWidth) * kMergeLeftMarginFrac;
    if (prevRight >= rightMargin && curLeft <= leftMargin && curTop > prevBottom)
        return true;

    return false;
}

// Assemble recognized boxes into a single text block. Reading order is the
// top-to-bottom row order from sort_reading_order(); the merge strategy above
// then collapses over-segmented lines into one logical line. Vertical boxes
// keep one glyph per line. (Baimiao / 白描 is excluded -- see should_merge_lines.)
static std::string objects_to_text(const std::vector<Object>& objects, int imgWidth)
{
    // Drop empty (unrecognized) boxes first so lookahead is well-defined.
    std::vector<const Object*> boxes;
    boxes.reserve(objects.size());
    for (const auto& o : objects)
        if (!o.text.empty())
            boxes.push_back(&o);

    std::string out;
    const size_t n = boxes.size();
    for (size_t i = 0; i < n; ++i)
    {
        const Object& obj = *boxes[i];

        // Separator before this box (except the very first). A newline, unless
        // the merge strategy joins it to the previous box into one logical line.
        // Skip when the previous box already ended on a newline (vertical text).
        if (i > 0)
        {
            const Object& prev = *boxes[i - 1];
            if (!should_merge_lines(prev, obj, imgWidth) && !out.empty() && out.back() != '\n')
                out += '\n';
        }

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
    }
    // Trim trailing newline(s) (a final vertical box ends on one).
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
            result = objects_to_text(objects, rgb.cols);
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
