#include "common.hpp"

#include <sys/stat.h>

#include <filesystem>
#include <string>

#define JNI_METHOD(return_type, name)                                          \
  extern "C" JNIEXPORT return_type JNICALL                                     \
      Java_org_yabe_xreal_1client_ClientEnvironment_##name

/// @return false if it fails
JNI_METHOD(jboolean, clearRootfsTmpfsDirectories)(JNIEnv *env, jobject obj,
                                                  jstring rootfs_path_value) {
  const auto rootfs_path = jstring_to_string(env, rootfs_path_value);
  // /run directory created by `filesystem` package in Arch Linux
  // may creates it as 0555, so fix it
  chmod((rootfs_path + "/run").c_str(), 0755);
  for (const auto *directory : {"run", "tmp"}) {
    const auto path = std::filesystem::path(rootfs_path) / directory;
    std::error_code error;
    const auto status = std::filesystem::symlink_status(path, error);
    if (error || !std::filesystem::is_directory(status)) {
      LOG_ERR("invalid rootfs tmpfs directory `%s`: %s", path.c_str(),
              error ? error.message().c_str() : "not a directory");
      return JNI_FALSE;
    }

    std::filesystem::directory_iterator entries(path, error);
    if (error) {
      LOG_ERR("failed to open rootfs tmpfs directory `%s`: %s", path.c_str(),
              error.message().c_str());
      return JNI_FALSE;
    }
    for (const auto &entry : entries) {
      std::filesystem::remove_all(entry.path(), error);
      if (error) {
        LOG_ERR("failed to remove `%s`: %s", entry.path().c_str(),
                error.message().c_str());
        return JNI_FALSE;
      }
    }
  }
  return JNI_TRUE;
}
