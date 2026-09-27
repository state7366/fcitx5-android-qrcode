# OCRSCAN: on-device PP-OCRv5 (ncnn) text recognizer.
#
# Vendored static libs live under prebuilt/${ANDROID_ABI}/{ncnn,opencv}. Only
# arm64-v8a is shipped in this fork (other ABIs fall back to ppocrv5_stub.cpp,
# which reports the engine as unavailable so the Kotlin layer can show a clean
# error instead of failing to link). To add an ABI, drop the matching
# prebuilt/${ABI} directory created from nihui/ncnn-android-ppocrv5's build deps.

set(PP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})

if(EXISTS ${PP_DIR}/ncnn/lib/cmake/ncnn/ncnnConfig.cmake AND
   EXISTS ${PP_DIR}/opencv/libs/${ANDROID_ABI}/libopencv_core.a)

    set(ncnn_DIR ${PP_DIR}/ncnn/lib/cmake/ncnn)
    find_package(ncnn REQUIRED)

    # opencv-mobile ships only core + imgproc here; declare the static imports
    # manually (avoids OpenCVConfig.cmake's ABI-name dance) so link order is
    # correct (imgproc depends on core).
    set(OCV_LIB_DIR ${PP_DIR}/opencv/libs/${ANDROID_ABI})
    add_library(opencv_core STATIC IMPORTED)
    # Mirrors opencv-mobile's own OpenCVModules_arm64_v8a.cmake: the libs were
    # built with OpenMP and need dl/m/log at link time. imgproc chains onto core.
    set_target_properties(opencv_core PROPERTIES
        IMPORTED_LOCATION ${OCV_LIB_DIR}/libopencv_core.a
        INTERFACE_LINK_LIBRARIES "dl;m;log;-fopenmp;-static-openmp"
        INTERFACE_INCLUDE_DIRECTORIES ${PP_DIR}/opencv/include)
    add_library(opencv_imgproc STATIC IMPORTED)
    set_target_properties(opencv_imgproc PROPERTIES
        IMPORTED_LOCATION ${OCV_LIB_DIR}/libopencv_imgproc.a
        INTERFACE_LINK_LIBRARIES opencv_core)

    # Core: the recognizer + preprocessing. Inherits ncnn's -fno-rtti/-fno-exceptions
    # and -fopenmp (via INTERFACE_LINK_LIBRARIES), which is how opencv-mobile itself
    # was built, so there is no ABI mismatch.
    add_library(ppocrv5_core STATIC ppocrv5.cpp ppocrv5_preprocess.cpp)
    target_include_directories(ppocrv5_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(ppocrv5_core PRIVATE ncnn opencv_core opencv_imgproc)
    # OpenMP is needed for the parallel recognition loop (#ifdef _OPENMP).
    target_compile_options(ppocrv5_core PRIVATE -fopenmp)

    add_library(ppocrv5 SHARED ppocrv5_jni.cpp)
    # ppocrv5_jni.cpp includes ppocrv5.h (<net.h>), <opencv2/core/core.hpp> and
    # ppocrv5_preprocess.h, so it needs the ncnn + opencv include dirs explicitly
    # (they are not propagated across the static ppocrv5_core boundary for
    # *compile* usage). Do NOT link ncnn directly here: that would drag its
    # -fno-exceptions/-fno-rtti interface options into the JNI TU.
    target_include_directories(ppocrv5 PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${PP_DIR}/opencv/include
        ${PP_DIR}/ncnn/include/ncnn)
    target_link_libraries(ppocrv5
        ppocrv5_core
        jnigraphics log android)

else()

    message(WARNING "OCRSCAN: no ncnn/opencv prebuilt for ABI ${ANDROID_ABI}; "
            "building ppocrv5 stub (engine will report unavailable at runtime).")
    add_library(ppocrv5 SHARED ppocrv5_stub.cpp)

endif()
