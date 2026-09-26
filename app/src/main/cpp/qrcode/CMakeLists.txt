# QRSCAN: QR Code Scanner stub engine. Self-contained; parent CMakeLists only
# needs the single add_subdirectory(qrcode) line marked with QRSCAN.
add_library(qrcode MODULE qrcode.cpp)
target_link_libraries(qrcode Fcitx5::Core)

# Register the addon/inputmethod descriptors via the "config" install component,
# so installProjectConfig deploys them into app assets on every machine.
# (app/src/main/assets/usr is gitignored -- installed data must come from here,
# mirroring androidfrontend.conf's install rule.)
install(FILES "${CMAKE_CURRENT_SOURCE_DIR}/qrcode-addon.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon"
        COMPONENT config
        RENAME qrcode.conf)
install(FILES "${CMAKE_CURRENT_SOURCE_DIR}/qrcode-inputmethod.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/inputmethod"
        COMPONENT config
        RENAME qrcode.conf)
