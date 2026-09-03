package org.yabe.xreal;
import android.os.ParcelFileDescriptor;

interface ICompositorService {
  // ClientHost     -> CompositorHost 
  boolean isCompositorReady();
  void setConfigText(String configText);
  void addClientFd(in ParcelFileDescriptor pfd);
  // CompositorHost -> ClientHost
  void notifyCompositorReady();
  String createKeymap(String rules, String model, String layout, String variant, String options);
  int exec(in String[] argv);
}
