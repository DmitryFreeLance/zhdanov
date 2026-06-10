const statusEl = document.getElementById("status");
const accountsEl = document.getElementById("accounts");
const phoneInput = document.getElementById("phoneInput");
const copyExportScriptButton = document.getElementById("copyExportScriptButton");
const openExportHelperButton = document.getElementById("openExportHelperButton");
const storageStateFileInput = document.getElementById("storageStateFileInput");
const storageStateInput = document.getElementById("storageStateInput");
const importSessionButton = document.getElementById("importSessionButton");
const refreshButton = document.getElementById("refreshButton");

let sessionToken = null;
let importSessionInFlight = false;

const WB_EXPORT_SCRIPT = String.raw`(() => {
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
  const payload = {
    exportKind: "wb-miniapp-browser-export",
    url: location.href,
    origin: location.origin,
    hostname: location.hostname,
    userAgent: navigator.userAgent,
    exportedAt: new Date().toISOString(),
    cookies,
    localStorage: localStorageData
  };
  const text = JSON.stringify(payload, null, 2);
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(
      () => alert("JSON WB-сессии скопирован. Вернитесь в mini app и вставьте его."),
      () => console.log(text)
    );
  } else {
    console.log(text);
    alert("Не удалось скопировать автоматически. JSON выведен в консоль.");
  }
})();`;

init().catch((error) => {
  setStatus(error.message || "Не удалось открыть mini app.", "error");
});

async function init() {
  window.WebApp?.ready?.();
  const initData = window.WebApp?.initData;
  if (!initData) {
    throw new Error("Mini app нужно открывать из MAX.");
  }

  const session = await api("/api/miniapp/session", {
    method: "POST",
    body: JSON.stringify({ initData }),
  });

  sessionToken = session.sessionToken;
  applyPhoneFromQuery();
  setStatus(`Привет, ${session.firstName}. Подключайте аккаунты WB через импорт готовой сессии.`, "success");
  await reloadAccounts();
}

storageStateFileInput.addEventListener("change", async (event) => {
  const [file] = event.target.files || [];
  if (!file) {
    return;
  }
  try {
    storageStateInput.value = await file.text();
    setStatus("JSON storage state загружен из файла. Теперь нажмите «Импортировать сессию».", "success");
  } catch (error) {
    setStatus(error.message || "Не удалось прочитать файл storage state.", "error");
  }
});

importSessionButton.addEventListener("click", async () => {
  if (importSessionInFlight) {
    return;
  }
  try {
    const phoneNumber = phoneInput.value.trim();
    const storageStateJson = storageStateInput.value.trim();
    if (!phoneNumber) {
      throw new Error("Введите телефон WB.");
    }
    if (!storageStateJson) {
      throw new Error("Выберите файл storage state или вставьте JSON вручную.");
    }
    importSessionInFlight = true;
    importSessionButton.disabled = true;
    setStatus("Проверяю и импортирую WB-сессию…");
    const result = await api("/api/miniapp/wb-auth/import", {
      method: "POST",
      body: JSON.stringify({
        sessionToken,
        phoneNumber,
        storageStateJson,
      }),
    });
    storageStateFileInput.value = "";
    storageStateInput.value = "";
    setStatus(result.message || "WB-сессия импортирована.", "success");
    renderAccounts(result.accounts || []);
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    importSessionInFlight = false;
    importSessionButton.disabled = false;
  }
});

copyExportScriptButton.addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(WB_EXPORT_SCRIPT);
    setStatus("Скрипт скопирован. Откройте WB в обычном браузере, вставьте его в консоль и потом вставьте полученный JSON сюда.", "success");
  } catch (error) {
    setStatus("Не удалось скопировать скрипт автоматически. Разрешите доступ к буферу обмена.", "error");
  }
});

openExportHelperButton.addEventListener("click", () => {
  const helperUrl = buildExportHelperUrl(phoneInput.value.trim());
  window.open(helperUrl, "_blank", "noopener,noreferrer");
});

refreshButton.addEventListener("click", async () => {
  try {
    await reloadAccounts();
  } catch (error) {
    setStatus(error.message, "error");
  }
});

async function reloadAccounts() {
  const result = await api(`/api/miniapp/state?sessionToken=${encodeURIComponent(sessionToken)}`);
  renderAccounts(result.accounts || []);
}

function renderAccounts(accounts) {
  if (!accounts.length) {
    accountsEl.innerHTML = `<p class="muted">Аккаунты ещё не подключены.</p>`;
    return;
  }

  accountsEl.innerHTML = "";
  for (const account of accounts) {
    const wrapper = document.createElement("div");
    wrapper.className = "account";
    wrapper.innerHTML = `
      <div>
        <div class="account-title">${maskPhone(account.phoneNumber)}</div>
        <div class="account-meta">${account.enabled ? "Активен" : "На паузе"} • ${account.status || "CONNECTED"}</div>
      </div>
      <div class="account-actions">
        <button class="ghost" data-action="toggle">${account.enabled ? "Пауза" : "Включить"}</button>
        <button class="ghost" data-action="unlink">Отключить</button>
      </div>
    `;

    wrapper.querySelector('[data-action="toggle"]').addEventListener("click", async () => {
      try {
        const result = await api(`/api/miniapp/accounts/${account.accountId}/enabled`, {
          method: "POST",
          body: JSON.stringify({
            sessionToken,
            enabled: !account.enabled,
          }),
        });
        setStatus(!account.enabled ? "Аккаунт включён." : "Аккаунт поставлен на паузу.", "success");
        renderAccounts(result.accounts || []);
      } catch (error) {
        setStatus(error.message, "error");
      }
    });

    wrapper.querySelector('[data-action="unlink"]').addEventListener("click", async () => {
      try {
        const result = await api(`/api/miniapp/accounts/${account.accountId}?sessionToken=${encodeURIComponent(sessionToken)}`, {
          method: "DELETE",
        });
        setStatus("Аккаунт отключён от этого чата.", "success");
        renderAccounts(result.accounts || []);
      } catch (error) {
        setStatus(error.message, "error");
      }
    });

    accountsEl.appendChild(wrapper);
  }
}

function setStatus(message, kind = "") {
  statusEl.textContent = message;
  statusEl.className = `status ${kind}`.trim();
}

function applyPhoneFromQuery() {
  const params = new URLSearchParams(window.location.search);
  const phone = (params.get("phone") || "").trim();
  if (phone && !phoneInput.value.trim()) {
    phoneInput.value = phone;
  }
}

function buildExportHelperUrl(phoneNumber) {
  const url = new URL("/miniapp/export-helper", window.location.origin);
  if (phoneNumber) {
    url.searchParams.set("phone", phoneNumber);
  }
  if (sessionToken) {
    url.searchParams.set("sessionToken", sessionToken);
  }
  return url.toString();
}

async function api(url, options = {}) {
  const response = await fetch(url, {
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
    ...options,
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.success === false) {
    throw new Error(payload.message || "Ошибка запроса.");
  }
  return payload;
}

function maskPhone(phoneNumber) {
  const digits = String(phoneNumber || "").replace(/\D/g, "");
  if (digits.length < 4) {
    return phoneNumber || "без номера";
  }
  return `+${digits[0]}***${digits.slice(-4)}`;
}
