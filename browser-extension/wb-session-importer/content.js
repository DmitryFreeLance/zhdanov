const HELPER_PATH = "/miniapp/export-helper";
const STORAGE_KEY = "wbImporterConfig";

if (window.location.pathname === HELPER_PATH) {
  const params = new URLSearchParams(window.location.search);
  const sessionToken = params.get("sessionToken");
  const phoneNumber = params.get("phone");
  if (sessionToken) {
    chrome.storage.local.set({
      [STORAGE_KEY]: {
        botOrigin: window.location.origin,
        sessionToken,
        phoneNumber: phoneNumber || ""
      }
    });
  }
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type !== "wb-importer:collect-session") {
    return;
  }

  try {
    const cookies = Object.fromEntries(
      document.cookie
        .split(/;\s*/)
        .filter(Boolean)
        .map((part) => {
          const index = part.indexOf("=");
          return [
            decodeURIComponent(index >= 0 ? part.slice(0, index) : part),
            decodeURIComponent(index >= 0 ? part.slice(index + 1) : "")
          ];
        })
    );

    const localStorageData = {};
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      localStorageData[key] = localStorage.getItem(key);
    }

    sendResponse({
      ok: true,
      payload: {
        exportKind: "wb-miniapp-browser-export",
        url: window.location.href,
        origin: window.location.origin,
        hostname: window.location.hostname,
        userAgent: navigator.userAgent,
        exportedAt: new Date().toISOString(),
        cookies,
        localStorage: localStorageData
      }
    });
  } catch (error) {
    sendResponse({
      ok: false,
      message: error?.message || "Не удалось собрать WB-сессию."
    });
  }
});
