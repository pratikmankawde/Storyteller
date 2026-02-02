cmake_minimum_required(VERSION 3.14)
project(llama_jni C CXX)

set(CMAKE_CXX_STANDARD 17)

# Build our JNI library; llama.cpp is fetched and only the core library is built.
include(FetchContent)

# Options for llama.cpp: build only the library, no common/server/tools/examples
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "")
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "")
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "")

# GPU offloading: try Vulkan when NDK + Ninja + host compiler are available (vulkan-shaders-gen).
# JNI already tries GPU first at runtime and falls back to CPU.
set(GGML_VULKAN_TRY ON CACHE BOOL "Try to enable Vulkan (Ninja + host C/CXX for shader gen)")
if(GGML_VULKAN_TRY)
  get_filename_component(CMAKE_BIN_DIR "${CMAKE_COMMAND}" DIRECTORY)
  if(WIN32)
    set(NINJA_PATH "${CMAKE_BIN_DIR}/ninja.exe")
  else()
    set(NINJA_PATH "${CMAKE_BIN_DIR}/ninja")
  endif()
  if(NOT EXISTS "${NINJA_PATH}")
    find_program(NINJA_PATH NAMES ninja ninja.exe NO_CMAKE_FIND_ROOT_PATH)
  endif()
  set(HOST_C_COMPILER "")
  set(HOST_CXX_COMPILER "")
  # Prefer NDK's prebuilt host Clang (works on Windows without VS/Cygwin)
  set(NDK_ROOT "")
  if(DEFINED CMAKE_ANDROID_NDK AND CMAKE_ANDROID_NDK)
    set(NDK_ROOT "${CMAKE_ANDROID_NDK}")
  endif()
  if(NOT NDK_ROOT AND DEFINED ANDROID_NDK AND ANDROID_NDK)
    set(NDK_ROOT "${ANDROID_NDK}")
  endif()
  if(NOT NDK_ROOT AND DEFINED ENV{ANDROID_NDK})
    set(NDK_ROOT "$ENV{ANDROID_NDK}")
  endif()
  # Fallback: default SDK/NDK location (e.g. Windows: %LOCALAPPDATA%\Android\Sdk\ndk\26.1.10909125)
  if(NOT NDK_ROOT AND WIN32 AND DEFINED ENV{LOCALAPPDATA})
    set(NDK_ROOT "$ENV{LOCALAPPDATA}/Android/Sdk/ndk/26.1.10909125")
    if(NOT EXISTS "${NDK_ROOT}")
      set(NDK_ROOT "")
    endif()
  endif()
  # Determine if host is Windows (CMAKE_HOST_SYSTEM_NAME, not WIN32 which is target)
  set(HOST_IS_WINDOWS FALSE)
  if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(HOST_IS_WINDOWS TRUE)
  endif()
  message(STATUS "CMAKE_HOST_SYSTEM_NAME: ${CMAKE_HOST_SYSTEM_NAME}, HOST_IS_WINDOWS: ${HOST_IS_WINDOWS}")

  # For Vulkan shader generator, we need a NATIVE host compiler (not a cross-compiler).
  # NDK clang targets Android, so it can't be used to build host tools.
  # We need to find: Visual Studio cl.exe, LLVM for Windows, or MinGW gcc.
  set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM_SAVE ${CMAKE_FIND_ROOT_PATH_MODE_PROGRAM})
  set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)

  if(HOST_IS_WINDOWS)
    # Priority 1: Visual Studio cl.exe (best option for Windows native builds)
    find_program(HOST_C_COMPILER NAMES cl HINTS ENV VCToolsInstallDir PATH_SUFFIXES bin/Hostx64/x64 NO_CMAKE_FIND_ROOT_PATH)
    if(HOST_C_COMPILER)
      set(HOST_CXX_COMPILER "${HOST_C_COMPILER}")
      set(HOST_CXX_COMPILER_ID "MSVC")
      set(HOST_CXX_COMPILER_VERSION "19")
      message(STATUS "Vulkan host compiler: Visual Studio cl.exe")
    endif()

    # Priority 2: LLVM for Windows WITH Visual Studio libs
    # Note: LLVM clang alone cannot link executables on Windows - it needs MSVC runtime libs.
    # We only enable LLVM if Visual Studio is also installed (provides libcmt.lib, etc.)
    if(NOT HOST_C_COMPILER)
      # Check if MSVC libs are available (via Visual Studio or Windows SDK)
      set(MSVC_LIBS_FOUND FALSE)
      # Look for vcvarsall.bat or lib.exe as indicators of VS installation
      file(GLOB VS_PATHS "C:/Program Files/Microsoft Visual Studio/*/*/VC/Tools/MSVC/*/lib/x64")
      if(VS_PATHS)
        set(MSVC_LIBS_FOUND TRUE)
      endif()

      if(MSVC_LIBS_FOUND)
        set(LLVM_PATHS
          "C:/Program Files/LLVM/bin"
          "C:/LLVM/bin"
          "$ENV{ProgramFiles}/LLVM/bin"
        )
        foreach(LLVM_PATH ${LLVM_PATHS})
          if(EXISTS "${LLVM_PATH}/clang.exe" AND NOT HOST_C_COMPILER)
            set(HOST_C_COMPILER "${LLVM_PATH}/clang.exe")
            set(HOST_CXX_COMPILER "${LLVM_PATH}/clang++.exe")
            if(EXISTS "${LLVM_PATH}/llvm-rc.exe")
              set(HOST_RC_COMPILER "${LLVM_PATH}/llvm-rc.exe")
            endif()
            if(EXISTS "${LLVM_PATH}/lld-link.exe")
              set(HOST_LINKER "${LLVM_PATH}/lld-link.exe")
            endif()
            set(HOST_CXX_COMPILER_ID "Clang")
            set(HOST_CXX_COMPILER_VERSION "14")
            message(STATUS "Vulkan host compiler: LLVM for Windows at ${LLVM_PATH} (with MSVC libs)")
          endif()
        endforeach()
      else()
        # LLVM found but no MSVC libs - can't use it
        if(EXISTS "C:/Program Files/LLVM/bin/clang.exe")
          message(STATUS "LLVM for Windows found but MSVC runtime libs missing - cannot use for Vulkan")
        endif()
      endif()
    endif()

    # Priority 3: MinGW-w64 gcc (Windows native gcc)
    if(NOT HOST_C_COMPILER)
      # Explicitly check MSYS2 MinGW64 path first
      if(EXISTS "C:/msys64/mingw64/bin/gcc.exe")
        set(HOST_C_COMPILER "C:/msys64/mingw64/bin/gcc.exe")
        set(HOST_CXX_COMPILER "C:/msys64/mingw64/bin/g++.exe")
        set(HOST_TOOLCHAIN_TYPE "MINGW")
        set(HOST_CXX_COMPILER_ID "GNU")
        set(HOST_CXX_COMPILER_VERSION "11")
        message(STATUS "Vulkan host compiler: MinGW-w64 gcc at C:/msys64/mingw64/bin")
      else()
        # Fallback to find_program
        find_program(HOST_C_COMPILER NAMES x86_64-w64-mingw32-gcc gcc HINTS "C:/msys64/mingw64/bin" "C:/mingw64/bin" NO_CMAKE_FIND_ROOT_PATH)
        find_program(HOST_CXX_COMPILER NAMES x86_64-w64-mingw32-g++ g++ HINTS "C:/msys64/mingw64/bin" "C:/mingw64/bin" NO_CMAKE_FIND_ROOT_PATH)
        if(HOST_C_COMPILER AND NOT HOST_C_COMPILER MATCHES "[Cc]ygwin")
          set(HOST_TOOLCHAIN_TYPE "MINGW")
          set(HOST_CXX_COMPILER_ID "GNU")
          set(HOST_CXX_COMPILER_VERSION "11")
          message(STATUS "Vulkan host compiler: MinGW-w64 gcc (via find_program)")
        else()
          set(HOST_C_COMPILER "")
          set(HOST_CXX_COMPILER "")
        endif()
      endif()
    endif()

    # Note: We intentionally do NOT use NDK clang as it targets Android, not Windows
    if(NOT HOST_C_COMPILER)
      message(STATUS "Vulkan host compiler: NOT FOUND")
      message(STATUS "To enable Vulkan GPU acceleration, install one of:")
      message(STATUS "  1. Visual Studio Build Tools (recommended): winget install Microsoft.VisualStudio.2022.BuildTools")
      message(STATUS "  2. MinGW-w64 via MSYS2: winget install MSYS2.MSYS2, then pacman -S mingw-w64-x86_64-gcc")
      message(STATUS "  (Note: LLVM alone is not sufficient - it needs MSVC or MinGW runtime libraries)")
    endif()
  else()
    # Non-Windows host: use system gcc or clang
    find_program(HOST_C_COMPILER NAMES gcc clang NO_CMAKE_FIND_ROOT_PATH)
    find_program(HOST_CXX_COMPILER NAMES g++ clang++ NO_CMAKE_FIND_ROOT_PATH)
    if(HOST_C_COMPILER)
      if(HOST_CXX_COMPILER MATCHES "clang")
        set(HOST_CXX_COMPILER_ID "Clang")
        set(HOST_CXX_COMPILER_VERSION "14")
      else()
        set(HOST_CXX_COMPILER_ID "GNU")
        set(HOST_CXX_COMPILER_VERSION "11")
      endif()
      message(STATUS "Vulkan host compiler: ${HOST_C_COMPILER}")
    endif()
  endif()

  set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM ${CMAKE_FIND_ROOT_PATH_MODE_PROGRAM_SAVE})
  if(NINJA_PATH AND HOST_C_COMPILER AND HOST_CXX_COMPILER)
    set(GGML_VULKAN ON CACHE BOOL "")
    configure_file(
      ${CMAKE_CURRENT_SOURCE_DIR}/host-toolchain-vulkan.cmake.in
      ${CMAKE_CURRENT_BINARY_DIR}/host-toolchain-vulkan.cmake
      @ONLY
    )
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_CURRENT_BINARY_DIR}/host-toolchain-vulkan.cmake" CACHE FILEPATH "")
    message(STATUS "Vulkan: ON (host toolchain: Ninja + host compilers)")
  else()
    set(GGML_VULKAN OFF CACHE BOOL "")
    if(NOT NINJA_PATH)
      message(STATUS "Vulkan: OFF (Ninja not found)")
    elseif(NOT HOST_C_COMPILER OR NOT HOST_CXX_COMPILER)
      message(STATUS "Vulkan: OFF (native host compiler not found; install VS Build Tools, LLVM for Windows, or MinGW-w64)")
    else()
      message(STATUS "Vulkan: OFF (could not enable)")
    endif()
  endif()
else()
  set(GGML_VULKAN OFF CACHE BOOL "")
endif()

# Vulkan SDK headers (C++ bindings for Vulkan, not included in Android NDK)
# The Vulkan SDK provides vulkan.hpp which is needed for ggml-vulkan.cpp
# It also provides glslc shader compiler needed to compile SPIR-V shaders
if(GGML_VULKAN)
  # Check for Vulkan SDK installation
  set(VULKAN_SDK_PATHS
    "D:/VulkanSDK/1.4.335.0"
    "$ENV{VULKAN_SDK}"
    "C:/VulkanSDK/1.4.335.0"
  )
  set(VULKAN_SDK_INCLUDE_DIR "")
  set(VULKAN_SDK_GLSLC "")
  foreach(SDK_PATH ${VULKAN_SDK_PATHS})
    if(EXISTS "${SDK_PATH}/Include/vulkan/vulkan.hpp")
      set(VULKAN_SDK_INCLUDE_DIR "${SDK_PATH}/Include")
      # Also find glslc shader compiler
      if(EXISTS "${SDK_PATH}/Bin/glslc.exe")
        set(VULKAN_SDK_GLSLC "${SDK_PATH}/Bin/glslc.exe")
      elseif(EXISTS "${SDK_PATH}/bin/glslc")
        set(VULKAN_SDK_GLSLC "${SDK_PATH}/bin/glslc")
      endif()
      message(STATUS "Vulkan SDK found: ${SDK_PATH}")
      break()
    endif()
  endforeach()

  if(NOT VULKAN_SDK_INCLUDE_DIR)
    message(WARNING "Vulkan SDK not found. Install from https://vulkan.lunarg.com/sdk/home")
    message(WARNING "Vulkan GPU acceleration will be disabled")
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
  else()
    message(STATUS "Vulkan SDK headers: ${VULKAN_SDK_INCLUDE_DIR}")
    # Set glslc for CMake's find_package(Vulkan COMPONENTS glslc)
    if(VULKAN_SDK_GLSLC)
      set(Vulkan_GLSLC_EXECUTABLE "${VULKAN_SDK_GLSLC}" CACHE FILEPATH "Vulkan GLSL shader compiler" FORCE)
      message(STATUS "Vulkan glslc: ${VULKAN_SDK_GLSLC}")
    else()
      message(WARNING "glslc not found in Vulkan SDK - shaders will not be compiled")
    endif()
  endif()
endif()

FetchContent_Declare(
  llama.cpp
  GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
  GIT_TAG        b7793
)
FetchContent_MakeAvailable(llama.cpp)

# Add Vulkan SDK include path to ggml-vulkan target if Vulkan is enabled
if(GGML_VULKAN AND TARGET ggml-vulkan AND VULKAN_SDK_INCLUDE_DIR)
  target_include_directories(ggml-vulkan PRIVATE "${VULKAN_SDK_INCLUDE_DIR}")
  message(STATUS "Added Vulkan SDK include path to ggml-vulkan target")
endif()

# JNI library (llama target from FetchContent exports include dirs)
add_library(llama_jni SHARED llama_jni.cpp)
target_include_directories(llama_jni PRIVATE ${CMAKE_SOURCE_DIR} ${llama.cpp_SOURCE_DIR}/include)

# Link Vulkan if available (for GPU acceleration)
if(GGML_VULKAN)
  find_library(VULKAN_LIB vulkan)
  if(VULKAN_LIB)
    target_link_libraries(llama_jni PRIVATE llama log ${VULKAN_LIB})
    message(STATUS "Vulkan library linked: ${VULKAN_LIB}")
  else()
    target_link_libraries(llama_jni PRIVATE llama log)
    message(STATUS "Vulkan library not found, GPU offload may not work at runtime")
  endif()
else()
  target_link_libraries(llama_jni PRIVATE llama log)
endif()

target_compile_options(llama_jni PRIVATE -Wall -Wextra)
# Release: maximum optimization for performance
if(CMAKE_BUILD_TYPE STREQUAL "Release")
  target_compile_options(llama_jni PRIVATE -O3 -DNDEBUG)
endif()

# Report final Vulkan status
if(GGML_VULKAN)
  message(STATUS "==> Vulkan GPU acceleration: ENABLED")
else()
  message(STATUS "==> Vulkan GPU acceleration: DISABLED (CPU-only inference)")
endif()
