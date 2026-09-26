# QRSCAN: QR Code Scanner stub engine. Self-contained; parent CMakeLists only
# needs the single add_subdirectory(qrcode) line marked with QRSCAN.
add_library(qrcode MODULE qrcode.cpp)
target_link_libraries(qrcode Fcitx5::Core)
