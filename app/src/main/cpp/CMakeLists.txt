cmake_minimum_required(VERSION 3.22)
project(whisper_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

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
