# OCRSCAN: OCR (text recognition) stub engine. Self-contained; parent CMakeLists
# only needs the single add_subdirectory(ocr) line marked with OCRSCAN.
add_library(ocr MODULE ocr.cpp)
target_link_libraries(ocr Fcitx5::Core)

# Register the addon/inputmethod descriptors via the "config" install component,
# so installProjectConfig deploys them into app assets on every machine.
# (app/src/main/assets/usr is gitignored -- installed data must come from here,
# mirroring androidfrontend.conf's install rule.)
install(FILES "${CMAKE_CURRENT_SOURCE_DIR}/ocr-addon.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon"
        COMPONENT config
        RENAME ocr.conf)
install(FILES "${CMAKE_CURRENT_SOURCE_DIR}/ocr-inputmethod.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/inputmethod"
        COMPONENT config
        RENAME ocr.conf)
