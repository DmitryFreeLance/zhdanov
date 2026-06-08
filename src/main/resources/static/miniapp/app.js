const statusEl = document.getElementById("status");
const accountsEl = document.getElementById("accounts");
const phoneInput = document.getElementById("phoneInput");
const startAuthButton = document.getElementById("startAuthButton");
const codeSection = document.getElementById("codeSection");
const codeInput = document.getElementById("codeInput");
const confirmCodeButton = document.getElementById("confirmCodeButton");
const storageStateFileInput = document.getElementById("storageStateFileInput");
const storageStateInput = document.getElementById("storageStateInput");
const importSessionButton = document.getElementById("importSessionButton");
const refreshButton = document.getElementById("refreshButton");

let sessionToken = null;
let pendingFlowId = null;
let pendingPhoneNumber = null;
let startAuthInFlight = false;
let confirmAuthInFlight = false;
let importSessionInFlight = false;

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
  setStatus(`Привет, ${session.firstName}. Можно подключать аккаунты WB или импортировать готовую сессию.`, "success");
  await reloadAccounts();
}

startAuthButton.addEventListener("click", async () => {
  if (startAuthInFlight) {
    return;
  }
  try {
    const phoneNumber = phoneInput.value.trim();
    if (!phoneNumber) {
      throw new Error("Введите телефон WB.");
    }
    startAuthInFlight = true;
    startAuthButton.disabled = true;
    setStatus("Запрашиваю код у WB…");
    const result = await api("/api/miniapp/wb-auth/start", {
      method: "POST",
      body: JSON.stringify({ sessionToken, phoneNumber }),
    });
    pendingFlowId = result.flowId;
    pendingPhoneNumber = result.phoneNumber;
    codeSection.classList.remove("hidden");
    setStatus(result.message || "Код отправлен. Введите его ниже.", "success");
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    startAuthInFlight = false;
    startAuthButton.disabled = false;
  }
});

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

confirmCodeButton.addEventListener("click", async () => {
  if (confirmAuthInFlight) {
    return;
  }
  try {
    const code = codeInput.value.trim();
    if (!pendingFlowId || !pendingPhoneNumber) {
      throw new Error("Сначала запросите код.");
    }
    if (!code) {
      throw new Error("Введите код из SMS.");
    }
    confirmAuthInFlight = true;
    confirmCodeButton.disabled = true;
    setStatus("Подтверждаю код и сохраняю сессию WB…");
    const result = await api("/api/miniapp/wb-auth/confirm", {
      method: "POST",
      body: JSON.stringify({
        sessionToken,
        flowId: pendingFlowId,
        code,
        phoneNumber: pendingPhoneNumber,
      }),
    });
    pendingFlowId = null;
    pendingPhoneNumber = null;
    codeInput.value = "";
    codeSection.classList.add("hidden");
    setStatus("Аккаунт подключён.", "success");
    renderAccounts(result.accounts || []);
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    confirmAuthInFlight = false;
    confirmCodeButton.disabled = false;
  }
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
