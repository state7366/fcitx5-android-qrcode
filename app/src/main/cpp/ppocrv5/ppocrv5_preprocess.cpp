// OCRSCAN: see ppocrv5_preprocess.h for the rationale behind each step.
//
// This TU links against opencv-mobile the same way ppocrv5.cpp does (it inherits
// ncnn's -fno-rtti -fno-exceptions via the static `ppocrv5_core` library), which
// matches how opencv-mobile itself was compiled, so there is no ABI mismatch.
#include "ppocrv5_preprocess.h"

#include <algorithm>
#include <cstring>
#include <opencv2/imgproc/imgproc.hpp>

static const int kUpscaleMinSide = 960;
static const float kUpscaleMaxFactor = 2.0f;

void ppocrv5_preprocess(cv::Mat& rgb, unsigned int flags)
{
    if (rgb.empty())
        return;

    if (flags & PP_DENOISE)
    {
        cv::Mat tmp;
        cv::medianBlur(rgb, tmp, 3);
        rgb = tmp;
    }

    if (flags & PP_UPSCALE)
    {
        const int maxSide = std::max(rgb.cols, rgb.rows);
        if (maxSide < kUpscaleMinSide)
        {
            const float f = std::min(static_cast<float>(kUpscaleMinSide) / maxSide,
                                     kUpscaleMaxFactor);
            cv::Mat tmp;
            cv::resize(rgb, tmp, cv::Size(), f, f, cv::INTER_CUBIC);
            rgb = tmp;
        }
    }

    if (flags & PP_CLAHE)
    {
        // Work in Lab and equalise only L: keeps PP-OCR's expected colour balance
        // intact while boosting low-contrast glyphs.
        cv::Mat lab;
        cv::cvtColor(rgb, lab, cv::COLOR_RGB2Lab);
        std::vector<cv::Mat> channels(3);
        cv::split(lab, channels);
        cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(2.0, cv::Size(8, 8));
        clahe->apply(channels[0], channels[0]);
        cv::merge(channels, lab);
        cv::cvtColor(lab, rgb, cv::COLOR_Lab2RGB);
    }

    if (flags & PP_SHARPEN)
    {
        cv::Mat blurred;
        cv::GaussianBlur(rgb, blurred, cv::Size(0, 0), 1.0);
        // addWeighted into the same buffer is not guaranteed safe; use a temp.
        cv::Mat tmp;
        cv::addWeighted(rgb, 1.5, blurred, -0.5, 0.0, tmp);
        rgb = tmp;
    }
}

unsigned int ppocrv5_preprocess_flags(const char* spec, unsigned int fallback)
{
    if (spec == nullptr || spec[0] == '\0')
        return fallback;
    if (std::strcmp(spec, "none") == 0 || std::strcmp(spec, "off") == 0)
        return 0u;

    unsigned int flags = 0u;
    // crude comma/space separated tokeniser; matches whole words.
    const char* p = spec;
    const char* tok = spec;
    auto flush = [&]() {
        const size_t n = static_cast<size_t>(p - tok);
        if (n == 0)
            return;
        if (std::strncmp(tok, "upscale", n) == 0)
            flags |= PP_UPSCALE;
        else if (std::strncmp(tok, "clahe", n) == 0)
            flags |= PP_CLAHE;
        else if (std::strncmp(tok, "sharpen", n) == 0)
            flags |= PP_SHARPEN;
        else if (std::strncmp(tok, "denoise", n) == 0)
            flags |= PP_DENOISE;
    };
    while (true)
    {
        const char c = *p;
        if (c == ',' || c == ' ' || c == ';' || c == '\t' || c == '\n' || c == '\0')
        {
            flush();
            tok = p + 1;
            if (c == '\0')
                break;
        }
        ++p;
    }
    return flags == 0u ? fallback : flags;
}
