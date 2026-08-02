window.NativeAndroid = {
  available: function () { return typeof window.AndroidApp !== "undefined"; },
  toast: function (message) {
    if (this.available()) window.AndroidApp.showToast(String(message));
    else console.log(message);
  },
  share: async function (text) {
    if (this.available()) return window.AndroidApp.share(String(text));
    if (navigator.share) return navigator.share({text:String(text)});
  },
  copy: async function (text) {
    if (this.available()) return window.AndroidApp.copyText(String(text));
    return navigator.clipboard.writeText(String(text));
  },
  vibrate: function (milliseconds) {
    if (this.available()) return window.AndroidApp.vibrate(Number(milliseconds) || 100);
    if (navigator.vibrate) navigator.vibrate(Number(milliseconds) || 100);
  },
  openUrl: function (url) {
    if (this.available()) return window.AndroidApp.openUrl(String(url));
    window.open(String(url), "_blank", "noopener");
  },
  nativeIptvAvailable: function () {
    try { return this.available() && !!window.AndroidApp.supportsNativeIptv(); } catch(e) { return false; }
  },
  playNative: function (url,title,type,poster) {
    if (!this.nativeIptvAvailable()) throw new Error("Mode IPTV natif absent de cet APK");
    return window.AndroidApp.playNative(String(url),String(title||"Lecture"),String(type||"video"),String(poster||""));
  },
  castNative: function (url,title,mime,poster) {
    if (!this.nativeIptvAvailable()) throw new Error("Google Cast natif absent de cet APK");
    return window.AndroidApp.castNative(String(url),String(title||"Cast"),String(mime||"application/x-mpegURL"),String(poster||""));
  },
  saveDiagnostic: function (filename,text) {
    if (this.available() && window.AndroidApp.saveDiagnostic) return window.AndroidApp.saveDiagnostic(String(filename),String(text));
    return false;
  },
  saveSecret: function (key,value) {
    if (this.available() && window.AndroidApp.saveSecret) window.AndroidApp.saveSecret(String(key),String(value));
  },
  loadSecret: function (key) {
    if (this.available() && window.AndroidApp.loadSecret) return window.AndroidApp.loadSecret(String(key));
    return "";
  },
  startBackgroundTask: function (title, message) {
    if (this.available() && window.AndroidApp.startBackgroundTask) {
      return window.AndroidApp.startBackgroundTask(String(title),String(message));
    }
  },
  updateBackgroundTask: function (message) {
    if (this.available() && window.AndroidApp.updateBackgroundTask) {
      return window.AndroidApp.updateBackgroundTask(String(message));
    }
  },
  finishBackgroundTask: function (message, success) {
    if (this.available() && window.AndroidApp.finishBackgroundTask) {
      return window.AndroidApp.finishBackgroundTask(String(message),!!success);
    }
  }
};
