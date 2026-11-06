package org.yabe.xreal;

// implemented by ClientHost (xreal-client) and called by CompositorHost (xreal)
interface IClientHost {
  void notifyCompositorReady();
  String createKeymap(String rules, String model, String layout, String variant, String options);
  int exec(in String[] argv);
}
