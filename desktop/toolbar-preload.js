const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("rssWatchOverlay", {
  close: () => ipcRenderer.send("overlay:close"),
  openExternal: () => ipcRenderer.send("overlay:open-external"),
  currentUrl: () => ipcRenderer.invoke("overlay:url"),
});
