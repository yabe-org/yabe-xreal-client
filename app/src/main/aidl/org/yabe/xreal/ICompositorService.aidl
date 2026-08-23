package org.yabe.xreal;
import android.os.ParcelFileDescriptor;

interface ICompositorService {
  boolean isCompositorReady();
  void notifyCompositorReady();
  void addClientFd(in ParcelFileDescriptor pfd);
  String createKeymap(String rules, String model, String layout, String variant, String options);
  int exec(in String[] argv);
  void setConfigText(String configText);
}
