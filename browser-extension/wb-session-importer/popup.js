const STORAGE_KEY = "wbImporterConfig";
const statusEl = document.getElementById("status");
const phoneEl = document.getElementById("phone");
const originEl = document.getElementById("origin");
const importButton = document.getElementById("importButton");

let config = null;

init().catch((error) => {
  setStatus(error.message || "Не удалось открыть расширение.", "error");
});

async function init() {
  const stored = await chrome.storage.local.get(STORAGE_KEY);
  config = stored[STORAGE_KEY] || null;
  phoneEl.textContent = config?.phoneNumber || "не задан";
  originEl.textContent = config?.botOrigin || "не задан";
}

importButton.addEventListener("click", async () => {
  if (!config?.sessionToken || !config?.botOrigin || !config?.phoneNumber) {
    setStatus("Сначала открой helper-страницу из mini app. Она передаст расширению номер и токен.", "error");
    return;
  }

  importButton.disabled = true;
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab?.id || !tab.url || !tab.url.includes("wildberries.ru")) {
      throw new Error("Откройте активной вкладкой страницу WB Logistics после входа в аккаунт.");
    }

    const collected = await chrome.tabs.sendMessage(tab.id, { type: "wb-importer:collect-session" });
    if (!collected?.ok || !collected.payload) {
      throw new Error(collected?.message || "Не удалось собрать данные WB-сессии.");
    }

    const response = await fetch(`${config.botOrigin}/api/miniapp/wb-auth/import-external`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        sessionToken: config.sessionToken,
        phoneNumber: config.phoneNumber,
        storageStateJson: JSON.stringify(collected.payload)
      })
    });

    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.success === false) {
      throw new Error(payload.message || "Бот не принял WB-сессию.");
    }

    setStatus("WB-сессия успешно отправлена в бот. Можно возвращаться в mini app.", "success");
  } catch (error) {
    setStatus(error.message || "Не удалось импортировать WB-сессию.", "error");
  } finally {
    importButton.disabled = false;
  }
});

function setStatus(message, kind = "") {
  statusEl.textContent = message;
  statusEl.className = `status ${kind}`.trim();
}
