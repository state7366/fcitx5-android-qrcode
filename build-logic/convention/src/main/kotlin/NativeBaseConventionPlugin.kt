/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

open class NativeBaseConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val prebuiltDir = target.rootProject.projectDir.resolve("lib/fcitx5/src/main/cpp/prebuilt")
        // QRSCAN: in-repo tools (ECM + gettext shim), overridable via env var or
        // gradle property (env > property > in-repo default). See BUILDING.md.
        val toolsDir = target.rootProject.projectDir.resolve("tools").invariantSeparatorsPath
        val isBuildingBundle = target.rootProject.gradle.startParameter.taskNames.any {
            it.startsWith("${target.path}:bundle")
        }
        target.extensions.configure<CommonExtension> {
            ndkVersion = target.ndkVersion
            defaultConfig.apply {
                minSdk = Versions.minSdk
                @Suppress("UnstableApiUsage")
                externalNativeBuild {
                    cmake {
                        arguments(
                            "-DANDROID_STL=c++_shared",
                            "-DVERSION_NAME=${Versions.baseVersionName}",
                            "-DPREBUILT_DIR=${prebuiltDir.absolutePath}",
                            // QRSCAN-BEGIN: host-side ECM + gettext shim discovery for
                            // Windows builds. Resolution: env var > gradle property >
                            // in-repo tools/ default. See BUILDING.md.
                            "-DECM_DIR=${target.ep("ECM_DIR", "ECM_DIR") { "$toolsDir/ecm/share/ECM/cmake" }}",
                            "-DGETTEXT_MSGFMT_EXECUTABLE=${target.ep("GETTEXT_MSGFMT_EXECUTABLE", "GETTEXT_MSGFMT_EXECUTABLE") { "$toolsDir/gettext/msgfmt.cmd" }}",
                            "-DGETTEXT_MSGMERGE_EXECUTABLE=${target.ep("GETTEXT_MSGMERGE_EXECUTABLE", "GETTEXT_MSGMERGE_EXECUTABLE") { "$toolsDir/gettext/msgmerge.cmd" }}",
                            // fcitx5's FindECM.cmake ignores -DECM_DIR on Windows and
                            // only honors the env var; inject it via project-include.
                            "-DQRSCAN_ECM_DIR=${target.ep("ECM_DIR", "ECM_DIR") { "$toolsDir/ecm/share/ECM/cmake" }}",
                            "-DCMAKE_PROJECT_INCLUDE=$toolsDir/cmake/qrscan-windows-env.cmake",
                            // QRSCAN-END
                        )
                    }
                }
            }
            externalNativeBuild.apply {
                cmake {
                    version = target.cmakeVersion
                    path("src/main/cpp/CMakeLists.txt")
                }
            }
            // split apks should be disabled when building bundle
            // https://issuetracker.google.com/issues/402800800
            if (!isBuildingBundle) {
                splits.abi {
                    isEnable = true
                    isUniversalApk = false
                    reset()
                    (target.buildAbiOverride?.split(",") ?: Versions.supportedABIs).forEach {
                        include(it)
                    }
                }
            }
        }
        registerCleanCxxTask(target)
    }

    private fun registerCleanCxxTask(project: Project) {
        project.tasks.register<Delete>("cleanCxxIntermediates") {
            delete(project.file(".cxx"))
        }.also {
            project.cleanTask.dependsOn(it)
        }
    }

}
