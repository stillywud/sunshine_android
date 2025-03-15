# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION ${CMAKE_VERSION}) # this file comes with cmake

# If CMAKE_DISABLE_SOURCE_CHANGES is set to true and the source directory is an
# existing directory in our source tree, calling file(MAKE_DIRECTORY) on it
# would cause a fatal error, even though it would be a no-op.
if(NOT EXISTS "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix/src/openssl")
  file(MAKE_DIRECTORY "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix/src/openssl")
endif()
file(MAKE_DIRECTORY
  "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix/src/openssl-build"
  "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix"
  "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix/tmp"
  "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix/src/openssl-stamp"
  "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix/src"
  "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix/src/openssl-stamp"
)

set(configSubDirs )
foreach(subDir IN LISTS configSubDirs)
    file(MAKE_DIRECTORY "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix/src/openssl-stamp/${subDir}")
endforeach()
if(cfgdir)
  file(MAKE_DIRECTORY "/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/.cxx/Debug/6sy1242v/x86/dependencies/openssl-cmake/openssl-prefix/src/openssl-stamp${cfgdir}") # cfgdir has leading slash
endif()
