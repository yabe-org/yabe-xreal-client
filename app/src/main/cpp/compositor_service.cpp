#include "common.hpp"

#include <xkbcommon/xkbcommon.h>

#include <cstdlib>
#include <string>

#define JNI_METHOD(return_type, name)                                          \
  extern "C" JNIEXPORT return_type JNICALL                                     \
      Java_org_yabe_xreal_1client_CompositorService_##name

JNI_METHOD(jstring, createKeymap)(JNIEnv *env, jobject obj,
                                  jstring include_directory_value,
                                  jstring rules_value, jstring model_value,
                                  jstring layout_value, jstring variant_value,
                                  jstring options_value) {
  const auto include_directory =
      jstring_to_string(env, include_directory_value);
  const auto rules = jstring_to_string(env, rules_value);
  const auto model = jstring_to_string(env, model_value);
  const auto layout = jstring_to_string(env, layout_value);
  const auto variant = jstring_to_string(env, variant_value);
  const auto options = jstring_to_string(env, options_value);
  const xkb_rule_names names = {
      .rules = rules.c_str(),
      .model = model.c_str(),
      .layout = layout.c_str(),
      .variant = variant.c_str(),
      .options = options.c_str(),
  };

  auto *context = xkb_context_new(XKB_CONTEXT_NO_DEFAULT_INCLUDES);
  if (!context) {
    LOG_ERR("failed to create xkb context");
    return nullptr;
  }
  if (xkb_context_include_path_append(context, include_directory.c_str()) !=
      1) {
    LOG_ERR("failed to add xkb include path: %s", include_directory.c_str());
    xkb_context_unref(context);
    return nullptr;
  }

  auto *keymap = xkb_keymap_new_from_names2(
      context, &names, XKB_KEYMAP_FORMAT_TEXT_V1, XKB_KEYMAP_COMPILE_NO_FLAGS);
  xkb_context_unref(context);
  if (!keymap) {
    LOG_ERR("failed to create xkb keymap");
    return nullptr;
  }

  char *keymap_text =
      xkb_keymap_get_as_string(keymap, XKB_KEYMAP_FORMAT_TEXT_V1);
  xkb_keymap_unref(keymap);
  if (!keymap_text) {
    LOG_ERR("failed to serialize xkb keymap");
    return nullptr;
  }
  auto *result = env->NewStringUTF(keymap_text);
  free(keymap_text);
  return result;
}
