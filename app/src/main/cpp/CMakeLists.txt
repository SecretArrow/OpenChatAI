# Open Chat AI — binding JNI untuk llama.cpp (AI lokal on-device, GGUF).
# Di-build oleh AGP via externalNativeBuild; hanya ABI 64-bit agar build cepat.

cmake_minimum_required(VERSION 3.14)
project(openchai_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- Opsi llama.cpp / ggml: build minimal pustaka inti, tanpa contoh/server ----
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(openchai_llama SHARED llama_jni.cpp)

target_include_directories(openchai_llama PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
)

target_link_libraries(openchai_llama PRIVATE
    llama
    log
)

target_compile_options(openchai_llama PRIVATE -O3 -frtti -fexceptions)
