cmake_minimum_required(VERSION 3.31)
project(innocent_lab_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The Maven 1.2.2 Java API loads this target separately from its RIFE composition.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${BIL_MEDIA_SOURCE_ARCHIVE}")
file(SHA256 "${BIL_MEDIA_SOURCE_ARCHIVE}" media_sources_sha)
if(NOT media_sources_sha STREQUAL "966b20339632e9a63c69f80a5023897796e14302763bdd15346cecd1b57c1a19")
    message(FATAL_ERROR "Unexpected MeguvizMedia source archive for QCOM motion")
endif()
set(qcom_source "source/MeguvizMedia/src/android/qcom_motion")
file(ARCHIVE_EXTRACT INPUT "${BIL_MEDIA_SOURCE_ARCHIVE}"
    DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/media" PATTERNS "${qcom_source}/*")
set(MGM_WITH_QCOM_MOTION ON)
add_subdirectory("${CMAKE_CURRENT_BINARY_DIR}/media/${qcom_source}" qcom)
set_target_properties(meguviz_qcom_motion PROPERTIES LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
foreach(target meguviz_qcom_motion_core meguviz_qcom_motion)
    target_compile_options(${target} PRIVATE "$<$<CONFIG:Debug>:-O2>")
endforeach()
target_link_options(meguviz_qcom_motion PRIVATE "LINKER:-z,max-page-size=16384")

# Query the decoder's existing window without acquiring a second buffer producer.
add_library(bil_frame_window SHARED frame_window.cpp)
target_compile_definitions(bil_frame_window PRIVATE VK_USE_PLATFORM_ANDROID_KHR VK_NO_PROTOTYPES)
target_link_libraries(bil_frame_window PRIVATE android dl)
target_compile_options(bil_frame_window PRIVATE
    -Wall -Wextra -Werror -fvisibility=hidden
    -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti)
target_link_options(bil_frame_window PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--exclude-libs,ALL"
    "-Wl,--gc-sections"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/bil_frame_window.map")
set_property(TARGET bil_frame_window APPEND PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/bil_frame_window.map")

# Rust 只生成静态库，最终 JNI 共享库由 Android 工具链链接。
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
    target_compile_options(innocent_danmaku_wire PRIVATE
        -Wall -Wextra -Werror -fvisibility=hidden
        -fno-exceptions -fno-rtti -ffunction-sections -fdata-sections)
    # -fno-exceptions/-fno-rtti applied on innocent_danmaku_wire target above.
    target_link_options(innocent_danmaku_wire PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,--exclude-libs,ALL"
        "-Wl,--gc-sections"
        "-Wl,--icf=safe")
elseif(CMAKE_BUILD_TYPE STREQUAL "Release")
    message(FATAL_ERROR "danmaku wire requires cargo (rustup with aarch64-linux-android and armv7-linux-androideabi targets) for release builds")
else()
    message(WARNING "danmaku wire skipped: cargo or supported ABI unavailable (debug tolerance; Kotlin falls back)")
endif()
