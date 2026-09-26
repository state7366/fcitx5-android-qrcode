plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    id("org.fcitx.fcitx5.android.native-lib-convention")
    id("org.fcitx.fcitx5.android.fcitx-headers")
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.libime"

    defaultConfig {
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // dummy "cmake" target
                    "cmake",
                    // libime
                    "IMECore",
                    "IMEPinyin",
                    "IMETable"
                )
                arguments(
                    // host-side gettext shims (msgfmt/msgmerge) for Windows build
                    "-DGETTEXT_MSGFMT_EXECUTABLE=${System.getenv("GETTEXT_MSGFMT_EXECUTABLE") ?: "C:/Users/pony/.workbuddy/gettext/msgfmt.cmd"}",
                    "-DGETTEXT_MSGMERGE_EXECUTABLE=${System.getenv("GETTEXT_MSGMERGE_EXECUTABLE") ?: "C:/Users/pony/.workbuddy/gettext/msgmerge.cmd"}",
                )
            }
        }
    }

    prefab {
        create("cmake") {
            headerOnly = true
            headers = "src/main/cpp/cmake"
        }
        val libimeHeadersPrefix = "build/headers/usr/include/LibIME"
        create("IMECore") {
            libraryName = "libIMECore"
            headers = libimeHeadersPrefix
        }
        create("IMEPinyin") {
            libraryName = "libIMEPinyin"
            headers = libimeHeadersPrefix
        }
        create("IMETable") {
            libraryName = "libIMETable"
            headers = libimeHeadersPrefix
        }
    }
}

dependencies {
    implementation(project(":lib:fcitx5"))
}
