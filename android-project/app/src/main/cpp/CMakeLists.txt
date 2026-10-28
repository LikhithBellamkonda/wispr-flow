cmake_minimum_required(VERSION 3.22.1)
project("flowwrite_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization flags for Vivo Y200e (Snapdragon 4 Gen 2: 2x Cortex-A78 + 6x Cortex-A55)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+dotprod+fp16 -O3 -ffast-math -funroll-loops)
endif()

# Disable llama.cpp tests, examples, and HTTP server
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Verify llama.cpp submodule/source directory is present
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    add_subdirectory(llama.cpp)
    set(HAS_LLAMA_CPP TRUE)
else()
    message(WARNING "llama.cpp submodule not found at src/main/cpp/llama.cpp. Run: git clone https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp")
    set(HAS_LLAMA_CPP FALSE)
endif()

add_library(
    flowwrite_jni
    SHARED
    flowwrite_jni.cpp
)

find_library(log-lib log)
find_library(android-lib android)

if(HAS_LLAMA_CPP)
    target_compile_definitions(flowwrite_jni PRIVATE FLOWWRITE_HAS_LLAMA_CPP=1)
    target_include_directories(
        flowwrite_jni
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
    )
    target_link_libraries(
        flowwrite_jni
        PRIVATE
        llama
        ggml
        ${log-lib}
        ${android-lib}
    )
else()
    target_compile_definitions(flowwrite_jni PRIVATE FLOWWRITE_HAS_LLAMA_CPP=0)
    target_link_libraries(
        flowwrite_jni
        PRIVATE
        ${log-lib}
        ${android-lib}
    )
endif()
