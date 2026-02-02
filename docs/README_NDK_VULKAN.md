# NDK and Vulkan (GPU) Build

## Locating the NDK

The build locates the NDK in this order:

1. **Gradle** passes `-DANDROID_NDK=<path>` when the SDK directory is known:
   - `ANDROID_HOME` or `ANDROID_SDK_ROOT`, or
   - `%LOCALAPPDATA%\Android\Sdk` (Windows fallback).
2. **CMake** uses `CMAKE_ANDROID_NDK`, then `ANDROID_NDK`, then `%ANDROID_NDK%`, then (on Windows) `%LOCALAPPDATA%\Android\Sdk\ndk\26.1.10909125` if that directory exists.

So the NDK is found even when `ANDROID_HOME` is not set, as long as it is installed in the default SDK location.

## How to set up NDK and Ninja on your machine

### 1. Install Android SDK + NDK + CMake (if not already)

- **Android Studio**: **File → Settings → Languages & Frameworks → Android SDK** (or **Tools → SDK Manager**).
  - **SDK** tab: ensure **Android SDK** is installed.
  - **SDK Tools** tab: enable **NDK (Side by side)** and **CMake**. Install **CMake 3.22.1** (or the version your project uses).
  - Click **Apply** and wait for install.

- **Command line** (no Android Studio):
  ```powershell
  # Typical SDK location on Windows
  $sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
  # Install NDK and CMake via sdkmanager (in Android SDK cmdline-tools)
  & "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat" "ndk;26.1.10909125" "cmake;3.22.1"
  ```

- This project expects **NDK 26.1.10909125** and **CMake 3.22.1**. Paths are usually:
  - NDK: `%LOCALAPPDATA%\Android\Sdk\ndk\26.1.10909125`
  - CMake: `%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1`
  - **Ninja** is inside the CMake folder: `%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1\bin\ninja.exe`

### 2. Make Ninja (and optionally NDK) visible to builds

- **Option A – Use Android Studio / Gradle only**  
  Gradle uses the SDK/NDK/CMake from the SDK path. You don’t need to set PATH for normal (non-Vulkan) builds. For Vulkan, the host tool that builds shaders must see Ninja; adding the CMake bin to PATH (see Option B) fixes that.

- **Option B – Add CMake bin to PATH (recommended for Vulkan)**  
  So that Ninja is found when the Vulkan shader generator runs:

  **Windows (current user, persistent):**
  1. **Win + R** → `sysdm.cpl` → **Advanced** → **Environment Variables**.
  2. Under **User variables** select **Path** → **Edit** → **New**.
  3. Add: `C:\Users\<YourUsername>\AppData\Local\Android\Sdk\cmake\3.22.1\bin`  
     (replace with your actual SDK path if different).
  4. **OK** and restart the terminal (and Android Studio if you use it).

  **Windows (current session only, PowerShell):**
  ```powershell
  $env:PATH = "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;$env:PATH"
  .\gradlew.bat assembleRelease
  ```

  **macOS / Linux:**
  ```bash
  export PATH="$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"   # macOS
  export PATH="$HOME/Android/Sdk/cmake/3.22.1/bin:$PATH"          # Linux
  ./gradlew assembleRelease
  ```

### 3. (Optional) For Vulkan: native host compiler on Windows

The Vulkan shader generator is a **host** program (runs on your PC). On Windows it needs a **native** C/C++ compiler (not Cygwin gcc):

- **Visual Studio Build Tools**  
  Install [Build Tools for Visual Studio](https://visualstudio.microsoft.com/visual-cpp-build-tools/) with the **“Desktop development with C++”** workload. Use a **Developer Command Prompt** or ensure `cl.exe` is on PATH.

- **LLVM/Clang for Windows**  
  Install [LLVM](https://releases.llvm.org/) and add its `bin` folder to PATH so `clang.exe` and `clang++.exe` are found before Cygwin’s `gcc`.

If you don’t enable Vulkan (`GGML_VULKAN_TRY` stays OFF), you don’t need this; the app builds and runs with CPU-only inference.

### 4. Verify

- Build the app (no Vulkan):  
  `.\gradlew.bat assembleRelease`  
  Should succeed with Vulkan OFF.

- If you enabled Vulkan: clean native build, then build again:
  ```powershell
  Remove-Item -Recurse -Force app\.cxx -ErrorAction SilentlyContinue
  .\gradlew.bat assembleRelease
  ```

---

## Vulkan build status (MinGW)

If you use **MinGW-w64** (e.g. MSYS2) as the host compiler for `vulkan-shaders-gen`, the Vulkan build may fail when compiling `vulkan-shaders-gen.cpp`: the compiler can exit with code 1 and no error output (known with long build paths). **Recommendation:** use **Visual Studio Build Tools** as the host compiler for reliable Vulkan builds on Windows, or build the project from a shorter path (e.g. `C:\st`).

## Default behavior

- **Vulkan is OFF** by default if no suitable host compiler is found. The app builds with CPU-only llama.cpp and runs correctly.
- JNI already tries GPU first at runtime and falls back to CPU; `getExecutionProvider()` reports which is used.

## Enabling Vulkan (GPU offload)

To build with Vulkan so the app can use GPU on supported devices:

1. **Ninja** must be available when CMake runs. The Android SDK’s CMake (e.g. `Android/Sdk/cmake/3.22.1/bin`) includes `ninja.exe`; that directory is used automatically when it’s next to `cmake`.
2. **Host C/CXX compiler** for `vulkan-shaders-gen` (a host tool built during the Android cross-compile):
   - **Windows**: Use a **native** compiler (Visual Studio Build Tools `cl`, or LLVM/Clang for Windows). **Do not use Cygwin gcc** (it often fails with Ninja).
   - **macOS/Linux**: Standard `gcc`/`clang` from PATH is fine.

3. **Turn Vulkan on** when configuring:
   - In Android Studio: add to CMake arguments: `-DGGML_VULKAN_TRY=ON`.
   - Or from command line, clean and build with the cache option:
     ```bash
     # Windows (PowerShell)
     Remove-Item -Recurse -Force app\.cxx -ErrorAction SilentlyContinue
     .\gradlew.bat assembleRelease -Pandroid.injected.build.abi=arm64-v8a
     ```
     Then in `app/src/main/cpp/CMakeLists.txt` set `set(GGML_VULKAN_TRY ON CACHE BOOL "")` (or pass `-DGGML_VULKAN_TRY=ON` via Gradle CMake arguments if your setup supports it).

4. **If Vulkan build fails**:
   - Ensure Ninja is on PATH (e.g. add `Android/Sdk/cmake/3.22.1/bin`).
   - On Windows, if you see “Cygwin gcc” or “make failed”, install Visual Studio Build Tools or LLVM/Clang for Windows and ensure that compiler is first in PATH (or remove Cygwin from PATH when building).
   - To go back to CPU-only, set `GGML_VULKAN_TRY=OFF` (or remove the cache override) and delete `app/.cxx` before rebuilding.

## NDK version

The project uses **NDK 26.1.10909125** (`ndkVersion` in `app/build.gradle.kts`). Install it via SDK Manager or set `ANDROID_NDK` to your NDK root if you use a different path.
