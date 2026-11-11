cmake_minimum_required(VERSION 3.31)
project(innocent_frame_interpolation LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
set(ncnn_archive "https://github.com/Tencent/ncnn/releases/download/20261020/ncnn-20261020-android-vulkan.zip")
if(INNOCENT_NCNN_ARCHIVE)
    set(ncnn_archive "${INNOCENT_NCNN_ARCHIVE}")
endif()
FetchContent_Declare(ncnn_android
    URL "${ncnn_archive}"
    URL_HASH SHA256=6e0c5f9b397faf32b923de2ef6210af9bf375851869dceff06a8005331981086
    DOWNLOAD_EXTRACT_TIMESTAMP FALSE)
FetchContent_MakeAvailable(ncnn_android)
find_package(ncnn 20261020 EXACT CONFIG REQUIRED NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH
    PATHS "${ncnn_android_SOURCE_DIR}/${ANDROID_ABI}/lib/cmake/ncnn")

add_subdirectory(player_interpolation)
add_library(innocent_frame_interpolation SHARED frame_jni.cpp)
target_link_libraries(innocent_frame_interpolation PRIVATE innocent_frame_engine)
target_compile_options(innocent_frame_interpolation PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
set_source_files_properties(frame_jni.cpp PROPERTIES COMPILE_OPTIONS -fexceptions)
target_link_options(innocent_frame_interpolation PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")

# A private per-variant copy, independent of AGP's filtered merge artifact.
if(INNOCENT_FRAME_PAYLOAD_DIR)
    if(NOT INNOCENT_FRAME_BUILD_KEY MATCHES "^[0-9a-f]+$")
        message(FATAL_ERROR "A frame payload requires its build-owned source/configuration key")
    endif()
    set(frame_payload "${INNOCENT_FRAME_PAYLOAD_DIR}/${ANDROID_ABI}/libinnocent_frame_interpolation.so")
    set(frame_receipt "${INNOCENT_FRAME_PAYLOAD_DIR}/${ANDROID_ABI}/frame-build-key.txt")
    set(frame_key_source "${CMAKE_CURRENT_BINARY_DIR}/frame-build-key.txt")
    file(GENERATE OUTPUT "${frame_key_source}" CONTENT "${INNOCENT_FRAME_BUILD_KEY}\n${ANDROID_ABI}\n" NEWLINE_STYLE UNIX)
    set_property(TARGET innocent_frame_interpolation APPEND PROPERTY LINK_DEPENDS "${frame_key_source}")
    add_custom_command(TARGET innocent_frame_interpolation POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${INNOCENT_FRAME_PAYLOAD_DIR}/${ANDROID_ABI}"
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:innocent_frame_interpolation>" "${frame_payload}"
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${frame_key_source}" "${frame_receipt}"
        BYPRODUCTS "${frame_payload}" "${frame_receipt}"
        VERBATIM)
endif()

# ---- 弹幕净化 wire 内核：Rust 静态库 + JNI 垫片（由 Android 原生构建打包）----
# 与帧引擎共用工具链但独立成库：帧引擎体量大、按需开启，走资产化校验解压链；本库体量小、
# 净化路径常驻，直接由 AGP externalNativeBuild 默认收进 APK lib/<abi>/（minSdk>=23 时
# STORED 存储，宿主进程 System.loadLibrary 加载，与 dexkit 同一已验证路线）。
#
# 硬约束：本目标的失败**绝不能 FATAL 波及帧引擎**——两个 SHARED 目标共用一次 CMake
# configure，FATAL 会让帧收据缺失、连带炸掉 assembleDebug。缺 cargo/ABI 时 debug 构建
# 降级为告警 + 不产出（Kotlin 侧回退 JVM 扫描/反射路径），release 构建仍硬失败。
# staticlib 产物不需要 NDK linker：cargo 只做编译 + ar 归档，链接由本工程 clang 完成。
find_program(DANMAKU_CARGO_EXECUTABLE cargo)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(danmaku_rust_triple aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(danmaku_rust_triple armv7-linux-androideabi)
else()
    set(danmaku_rust_triple "")
endif()
if(DANMAKU_CARGO_EXECUTABLE AND danmaku_rust_triple)
    set(danmaku_wire_dir "${CMAKE_CURRENT_SOURCE_DIR}/../rust/danmaku-wire")
    # target 目录必须留在 CMake 二进制目录：绝不放回源码树（会污染 sourceContractInputs
    # 的 fileTree("src/main") 输入指纹，且有把预构建 .a 误当 Ninja 最新产物的风险）。
    set(danmaku_wire_target_dir "${CMAKE_CURRENT_BINARY_DIR}/danmaku-wire-target")
    set(danmaku_wire_static "${danmaku_wire_target_dir}/${danmaku_rust_triple}/release/libdanmaku_wire.a")
    add_custom_command(
        OUTPUT "${danmaku_wire_static}"
        COMMAND "${CMAKE_COMMAND}" -E env "CARGO_TARGET_DIR=${danmaku_wire_target_dir}"
            "${DANMAKU_CARGO_EXECUTABLE}" build --release --offline --locked
            --target "${danmaku_rust_triple}" --manifest-path "${danmaku_wire_dir}/Cargo.toml"
        # cargo 可执行文件本身也入依赖：工具链就地升级必须触发重建。
        DEPENDS "${DANMAKU_CARGO_EXECUTABLE}"
            "${danmaku_wire_dir}/Cargo.toml" "${danmaku_wire_dir}/Cargo.lock"
            "${danmaku_wire_dir}/rust-toolchain.toml" "${danmaku_wire_dir}/src/lib.rs"
        COMMENT "cargo build danmaku-wire (${ANDROID_ABI})"
        VERBATIM)
    # custom target 承载生成顺序，.a 经全路径 target_link_libraries 上链接行：
    # 把 .a 直接当 add_library 源文件的隐式行为在 CMake 4.x 不可靠（实测不上链接行）。
    add_custom_target(danmaku_wire_rust_archive DEPENDS "${danmaku_wire_static}")
    add_library(innocent_danmaku_wire SHARED danmaku_wire_jni.cpp)
    add_dependencies(innocent_danmaku_wire danmaku_wire_rust_archive)
    target_link_libraries(innocent_danmaku_wire PRIVATE "${danmaku_wire_static}")
    target_compile_options(innocent_danmaku_wire PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
    set_source_files_properties(danmaku_wire_jni.cpp PROPERTIES COMPILE_OPTIONS -fexceptions)
    target_link_options(innocent_danmaku_wire PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
elseif(CMAKE_BUILD_TYPE STREQUAL "Release")
    message(FATAL_ERROR "danmaku wire requires cargo (rustup with aarch64-linux-android and armv7-linux-androideabi targets) for release builds")
else()
    message(WARNING "danmaku wire skipped: cargo or supported ABI unavailable (debug tolerance; Kotlin falls back)")
endif()
