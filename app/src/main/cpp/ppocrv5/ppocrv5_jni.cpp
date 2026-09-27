// OCRSCAN: JNI bridge for the on-device PP-OCRv5 (ncnn) text recognizer.
//
// The Kotlin side holds a long "handle" to a heap-allocated PPOCRv5 instance so
// re-running recognition does not reload the (multi-MB) model every call. Bitmap
// pixels are locked with the NDK bitmap API and converted to an RGB cv::Mat; the
// model expects RGB order, not the ARGB_8888 the Android side hands us.
#include <android/bitmap.h>
#include <android/log.h>
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

// OCRSCAN-DEBUG: dump every detection box (geometry + recognized text) to
// logcat so we can reproduce the engine's real output off-device. Tag:
// "PpOcrV5Dump". Grep logcat for that tag after a recognition run.
#define PPOCRV5_DUMP_TAG "PpOcrV5Dump"
static void dump_objects(const char* stage, const std::vector<Object>& objects,
                         int imgW, int imgH)
{
    __android_log_print(ANDROID_LOG_INFO, PPOCRV5_DUMP_TAG,
                        "[%s] img=%dx%d nBoxes=%d", stage, imgW, imgH, (int)objects.size());
    for (size_t i = 0; i < objects.size(); ++i)
    {
        const Object& o = objects[i];
        std::string s;
        for (const Character& ch : o.text)
            if (ch.id >= 0 && ch.id < character_dict_size)
                s += character_dict[ch.id];
        const cv::Point2f c = o.rrect.center;
        __android_log_print(ANDROID_LOG_INFO, PPOCRV5_DUMP_TAG,
                            "  #%02zu cy=%.1f cx=%.1f w=%.1f h=%.1f ang=%.1f ori=%d | %s",
                            i, c.y, c.x, o.rrect.size.width, o.rrect.size.height,
                            o.rrect.angle, o.orientation, s.c_str());
    }
}

// Sort boxes into reading order: top-to-bottom by row, with left-to-right
// order inside a row. This is the conventional reading order the user expects
// (NOT left-most-start-first).
//
// IMPORTANT: the previous comparator used a PAIR-DEPENDENT half-band
// (max(a.h, b.h) * 0.5) as the y-vs-x tie-breaker. That is NOT a strict weak
// ordering, so std::sort had undefined behaviour and scrambled the order
// differently depending on the box heights (fine for uniform boxes, garbage
// for a title + smaller body lines). The fix below is a VALID total order:
// quantize center.y into row buckets of ~median line height, then sort by
// (row, x). Quantizing makes the primary key a true function of each box, so
// the ordering is well-defined and stable.
static void sort_reading_order(std::vector<Object>& objects)
{
    if (objects.empty())
        return;
    // Robust row height: median of all box heights (resistant to one very tall
    // box like a title). Falls back to 1px so we never divide by zero.
    std::vector<float> heights;
    heights.reserve(objects.size());
    for (const auto& o : objects)
        heights.push_back(o.rrect.size.height);
    std::sort(heights.begin(), heights.end());
    const float rowH = std::max(heights[heights.size() / 2], 1.0f);

    std::sort(objects.begin(), objects.end(), [rowH](const Object& a, const Object& b) {
        const int ra = static_cast<int>(std::floor(a.rrect.center.y / rowH));
        const int rb = static_cast<int>(std::floor(b.rrect.center.y / rowH));
        if (ra != rb)
            return ra < rb;
        return a.rrect.center.x < b.rrect.center.x;
    });
}

// Newline-merge tuning: fractions of the image width treated as the right /
// left margin. A line that reaches (near) the right margin followed by a line
// starting at the left margin is treated as a wrapped continuation of the same
// line. 0.90 / 0.10 (NOT 0.96 / 0.04): a line that stops a little short of the
// full width -- common in real scans with page padding -- must still merge.
static constexpr float kMergeRightMarginFrac = 0.90f;
static constexpr float kMergeLeftMarginFrac  = 0.10f;

// Content cue: true if the recognized line begins with a list / section marker
// such as "1."  "2、"  "（3）"  "(4)"  "一、"  "·"  "*". Such a line starts a
// NEW logical line and must never be merged into the previous one. This is what
// disambiguates a cropped, full-width document, where geometry alone cannot
// (every line then reaches the right edge and starts at the left margin).
static bool starts_new_item(const std::string& text)
{
    size_t i = 0;
    while (i < text.size() &&
           (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r'))
        ++i;
    if (i >= text.size())
        return false;
    const unsigned char c = static_cast<unsigned char>(text[i]);

    // Arabic-numbered item: "1."  "2、"  "3)"  "1．"
    if (c >= '0' && c <= '9')
    {
        size_t j = i;
        while (j < text.size() && text[j] >= '0' && text[j] <= '9')
            ++j;
        while (j < text.size() && (text[j] == ' ' || text[j] == '\t'))
            ++j;
        if (j < text.size())
        {
            const unsigned char d = static_cast<unsigned char>(text[j]);
            if (d == '.' || d == ')' || text.compare(j, 3, "\xEF\xBC\x8E") == 0) // ．
                return true;
            if (text.compare(j, 3, "\xE3\x80\x81") == 0) // 、
                return true;
            if (text.compare(j, 3, "\xEF\xBC\x89") == 0) // ）
                return true;
        }
        return false;
    }

    // "（1）" / "(1)"
    if (text.compare(i, 3, "\xEF\xBC\x88") == 0 || c == '(')
    {
        const size_t j = (c == '(') ? i + 1 : i + 3;
        if (j < text.size() && text[j] >= '0' && text[j] <= '9')
            return true;
    }

    // bullet markers: · ・ • — * -
    if (text.compare(i, 2, "\xC2\xB7") == 0) return true;      // ·
    if (text.compare(i, 3, "\xE3\x83\xBB") == 0) return true;  // ・
    if (text.compare(i, 3, "\xE2\x80\xA2") == 0) return true;  // •
    if (text.compare(i, 3, "\xE2\x80\x94") == 0) return true;  // —
    if (c == '*' || c == '-') return true;

    // CJK-numbered item: 一、 二、 ... 十、
    static const char* kCjkNum[] = {
        "\xE4\xB8\x80", "\xE4\xBA\x8C", "\xE4\xB8\x89", "\xE5\x9B\x9B",
        "\xE4\xBA\x94", "\xE5\x85\xAD", "\xE4\xB8\x83", "\xE5\x85\xAB",
        "\xE4\xB9\x9D", "\xE5\x8D\x81"};
    for (const char* n : kCjkNum)
    {
        if (text.compare(i, 3, n) == 0)
        {
            const size_t j = i + 3;
            if (text.compare(j, 3, "\xE3\x80\x81") == 0) // 、
                return true;
            if (j < text.size() && text[j] == '.')
                return true;
        }
    }
    return false;
}

// Content cue: true if the recognized line ends with sentence-final punctuation
// (。 ？ ！ … ! ?), i.e. the logical line already finished.
static bool ends_sentence(const std::string& text)
{
    size_t i = text.size();
    while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t' ||
                     text[i - 1] == '\n' || text[i - 1] == '\r'))
        --i;
    if (i == 0)
        return false;
    if (i >= 3)
    {
        const std::string t = text.substr(i - 3, 3);
        if (t == "\xE3\x80\x82" || // 。
            t == "\xEF\xBC\x9F" || // ？
            t == "\xEF\xBC\x81" || // ！
            t == "\xE2\x80\xA6")   // …
            return true;
    }
    const char c = text[i - 1];
    return c == '!' || c == '?';
}

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
//      margin, the current line starts near the left margin and sits below it,
//      AND the current line does not itself start a new list item and the
//      previous line did not already end a sentence -> join, no '\n'.
//      Vertical boxes are never merged into anything.
static bool should_merge_lines(const Object& prev, const Object& cur,
                               const std::string& prevText, const std::string& curText,
                               int imgWidth)
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
    // the left margin and sits below prev. Content cues then VETO the join when
    // cur starts a new list item or prev already ended a sentence -- that is
    // what keeps numbered items / paragraphs from being glued together on a
    // cropped, full-width page. (No "cur is shorter" test: on a cropped page a
    // long continuation also touches the right edge, so it is unreliable.)
    const float rightMargin = static_cast<float>(imgWidth) * kMergeRightMarginFrac;
    const float leftMargin  = static_cast<float>(imgWidth) * kMergeLeftMarginFrac;
    if (prevRight >= rightMargin && curLeft <= leftMargin && curTop > prevBottom
        && !starts_new_item(curText) && !ends_sentence(prevText))
        return true;

    return false;
}

// Assemble recognized boxes into a single text block. Reading order is the
// top-to-bottom row order from sort_reading_order(); the merge strategy above
// then collapses over-segmented lines into one logical line. Vertical boxes
// keep one glyph per line. (Baimiao / 白描 is excluded -- see should_merge_lines.)
static std::string objects_to_text(const std::vector<Object>& objects, int imgWidth)
{
    // Drop empty (unrecognized) boxes and decode each line once, so the content
    // cues (starts_new_item / ends_sentence) can inspect the text cheaply.
    std::vector<const Object*> boxes;
    std::vector<std::string> texts;
    boxes.reserve(objects.size());
    texts.reserve(objects.size());
    for (const auto& o : objects)
    {
        if (o.text.empty())
            continue;
        std::string s;
        for (const Character& ch : o.text)
            if (ch.id >= 0 && ch.id < character_dict_size)
                s += character_dict[ch.id];
        if (s.empty())
            continue;
        boxes.push_back(&o);
        texts.push_back(std::move(s));
    }

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
            if (!should_merge_lines(prev, obj, texts[i - 1], texts[i], imgWidth)
                && !out.empty() && out.back() != '\n')
                out += '\n';
        }

        if (obj.orientation == 0)
        {
            out += texts[i];
        }
        else
        {
            // vertical text: stack glyphs, one per line
            for (const Character& ch : obj.text)
            {
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
            dump_objects("RAW", objects, rgb.cols, rgb.rows);
            sort_reading_order(objects);
            dump_objects("SORTED", objects, rgb.cols, rgb.rows);
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
