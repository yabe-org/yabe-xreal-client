#pragma once

#include <jni.h>

#include <android/log.h>
#include <string>

#define LOG_INFO(...)                                                          \
  __android_log_print(ANDROID_LOG_INFO, "YabeXrealClient", __VA_ARGS__)
#define LOG_DBG(...)                                                           \
  __android_log_print(ANDROID_LOG_DEBUG, "YabeXrealClient", __VA_ARGS__)
#define LOG_ERR(...)                                                           \
  __android_log_print(ANDROID_LOG_ERROR, "YabeXrealClient", __VA_ARGS__)

inline std::string jstring_to_string(JNIEnv *env, jstring value) {
  if (!value) {
    return {};
  }
  const char *chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) {
    return {};
  }
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}
