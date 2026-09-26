# QRSCAN: feed ECM_DIR to fcitx5's cmake/FindECM.cmake.
#
# FindECM.cmake (in the lib/fcitx5 submodule, unpatched upstream) decides the
# ECM location like this on Windows:
#     if(DEFINED ENV{ECM_DIR})  -> use env var
#     elseif(CMAKE_HOST_WIN32)  -> hardcoded C:/msys64/ucrt64/share/ECM/cmake
# i.e. a -DECM_DIR=... command line definition gets unconditionally overwritten
# when the environment variable is absent. We must not patch the submodule, so
# instead this file is injected via -DCMAKE_PROJECT_INCLUDE=... and runs as the
# last step of the top-level project() call -- before find_package(ECM) -- and
# sets the environment variable inside the cmake process itself.
#
# QRSCAN_ECM_DIR is passed on the command line by build-logic
# (NativeBaseConventionPlugin), resolved as: env var > gradle property >
# in-repo tools/ecm default.
if(NOT DEFINED ENV{ECM_DIR} AND DEFINED QRSCAN_ECM_DIR)
    set(ENV{ECM_DIR} "${QRSCAN_ECM_DIR}")
endif()
