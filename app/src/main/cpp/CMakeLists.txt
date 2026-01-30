cmake_minimum_required(VERSION 3.22.1)
project("llama_jni")

# Include llama.cpp using FetchContent
include(FetchContent)

# Set options before FetchContent_MakeAvailable so they're applied to llama.cpp
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama.cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG b6916
)

FetchContent_MakeAvailable(llama.cpp)

# Add JNI wrapper library (renamed to avoid conflict with llama.cpp's llama target)
add_library(llama_jni SHARED
    llama_jni.cpp
)

# Find required libraries
find_library(log-lib log)

# Link against llama.cpp's llama target
target_link_libraries(llama_jni
    PRIVATE
    llama
    ${log-lib}
)

# Include directories - llama.cpp headers are in include/ subdirectory
target_include_directories(llama_jni PRIVATE
    ${llama.cpp_SOURCE_DIR}/include
    ${llama.cpp_SOURCE_DIR}
)
