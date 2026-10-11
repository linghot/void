cmake_minimum_required(VERSION 3.22)
project(whisper_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# ★ 关键性能修复（v1.0）★
#
# AGP 给 debug variant 传的是 -DCMAKE_BUILD_TYPE=Debug，CMake 默认 Debug 配置为
# "-g"（零优化）。于是 whisper.cpp / ggml 全部按 -O0 编译，ggml 的矩阵运算会慢
# 10~50 倍 —— 实测表现就是"5 秒音频识别要 56 秒"。
#
# 这里把 Debug 配置的编译选项直接改成 -O3 -DNDEBUG：无论 AGP 传什么 BUILD_TYPE，
# 依赖库都是优化版。Release 也一并固定，避免别处被改坏。
# ---------------------------------------------------------------------------
set(CMAKE_C_FLAGS_DEBUG     "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)

# 让 ggml 走 ARM NEON（Android arm64 默认开启，这里显式确认一下）
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# 构建时拉取 whisper.cpp（CI 有网络，本机构建需要能访问 GitHub）
# 如果哪天这个 tag 不存在了，改成就近的版本号或 commit 即可
# ---------------------------------------------------------------------------
include(FetchContent)

# 只编我们需要的库，别编示例/测试/服务端，省一半以上编译时间
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)   # Android 上没有 OpenMP
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)   # 静态链接进我们的 jni 库

FetchContent_Declare(
        whisper
        GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
        GIT_TAG        v1.7.4
        GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper)

# ---------------------------------------------------------------------------
# 我们的 JNI 外壳：把 Kotlin 的 FloatArray 交给 whisper_full()，拿回文本
# ---------------------------------------------------------------------------
add_library(whisper_jni SHARED whisper_jni.cpp)

target_link_libraries(whisper_jni
        PRIVATE
        whisper      # whisper.cpp 编译出的静态库
        android      # 安卓日志等
        log)
