# Host toolchain for vulkan-shaders-gen when cross-compiling (e.g. Android NDK).
# Ensures Ninja is found so the ExternalProject configure step succeeds.
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
find_program(CMAKE_MAKE_PROGRAM NAMES ninja ninja.exe)
if(NOT CMAKE_MAKE_PROGRAM)
  message(FATAL_ERROR "Ninja not found. Install ninja or add it to PATH for Vulkan shader build.")
endif()
