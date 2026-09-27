// OCRSCAN: mild image pre-processing applied before feeding a still frame to the
// local PP-OCRv5 engine.
//
// PP-OCRv5 is trained on RGB photos, so the classic "binarize / deskew" recipe
// used for Tesseract actively hurts here. What does help -- and what "适度"
// (moderate) means for this codebase -- is:
//
//   * upscale : camera crops of a phone screen often have a long side below the
//               detection network's working resolution; cubic upscaling recovers
//               small glyphs. Capped at 2x so a thumbnail does not explode into a
//               multi-second inference.
//   * clahe   : contrast-limited adaptive histogram equalization on the L channel
//               of Lab, which lifts faint text without shifting colour.
//   * sharpen : unsharp mask, useful for slightly out-of-focus shots.
//   * denoise : 3x3 median blur, useful for noisy low-light shots.
//
// Everything is optional and defaults to (upscale | clahe); the combination is
// chosen from Kotlin via OcrConfig so users can trade speed for accuracy.
#ifndef PPOCRV5_PREPROCESS_H
#define PPOCRV5_PREPROCESS_H

#include <opencv2/core/core.hpp>

enum PpOcrPreprocessFlag
{
    PP_UPSCALE = 1 << 0,
    PP_CLAHE = 1 << 1,
    PP_SHARPEN = 1 << 2,
    PP_DENOISE = 1 << 3
};

/** Default combination: upscale + contrast enhancement. */
#define PPOCRV5_PREPROCESS_DEFAULT (PP_UPSCALE | PP_CLAHE)

/**
 * Apply the enabled steps, in place, to an RGB (CV_8UC3) image.
 * Never throws; an empty input is a no-op.
 */
void ppocrv5_preprocess(cv::Mat& rgb, unsigned int flags);

/** Parse "upscale,clahe,sharpen,denoise" (or "none") into a flag bitmask. */
unsigned int ppocrv5_preprocess_flags(const char* spec, unsigned int fallback);

#endif // PPOCRV5_PREPROCESS_H
