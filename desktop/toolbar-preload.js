const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("rssWatchOverlay", {
  close: () => ipcRenderer.send("overlay:close"),
  openExternal: () => ipcRenderer.send("overlay:open-external"),
  currentUrl: () => ipcRenderer.invoke("overlay:url"),
  onUrlChanged: (callback) =>
    ipcRenderer.on("overlay:url-changed", (_event, url) => callback(url)),
});
