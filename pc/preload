const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("NativeStore", {
  status: () => ipcRenderer.invoke("store:status"),
  choose: () => ipcRenderer.invoke("store:choose"),
  confirm: (folder) => ipcRenderer.invoke("store:confirm", folder),
  kvGet: () => ipcRenderer.invoke("store:kvGet"),
  kvSet: (key, value) => ipcRenderer.invoke("store:kvSet", key, value),
  version: () => ipcRenderer.invoke("store:version"),
  saveFile: (sub, name, data) => ipcRenderer.invoke("store:saveFile", sub, name, data),
  listFiles: (sub) => ipcRenderer.invoke("store:listFiles", sub),
  readFile: (sub, name) => ipcRenderer.invoke("store:readFile", sub, name),
  reveal: (p) => ipcRenderer.invoke("store:reveal", p),
  lanStatus: () => ipcRenderer.invoke("store:lanStatus"),
  lanSet: (cfg) => ipcRenderer.invoke("store:lanSet", cfg)
});
