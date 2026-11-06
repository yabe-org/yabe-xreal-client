package org.yabe.xreal;
import android.os.ParcelFileDescriptor;

// implemented by CompositorHost (xreal) and called by ClientHost (xreal-client)
interface ICompositorHost {
  boolean isCompositorReady();
  void setConfigText(String configText);
  void addClientFd(in ParcelFileDescriptor pfd);
}
