const helperStatusEl = document.getElementById("helperStatus");
const helperPhoneEl = document.getElementById("helperPhone");
const copyAndOpenButton = document.getElementById("copyAndOpenButton");
const copyOnlyButton = document.getElementById("copyOnlyButton");

const params = new URLSearchParams(window.location.search);
const phoneParam = (params.get("phone") || "").trim();
const sanitizedPhone = phoneParam.replace(/[^\d+]/g, "");
const wbUrl = "https://logistics.wildberries.ru/";

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

init();

function init() {
  helperPhoneEl.textContent = sanitizedPhone || "не передан";
  if (sanitizedPhone) {
    helperStatusEl.textContent = "Номер подставлен. Можно сразу открывать WB.";
    helperStatusEl.className = "status success";
  } else {
    helperStatusEl.textContent = "Номер не передан. Это не страшно: просто войдите в нужный WB-аккаунт вручную.";
    helperStatusEl.className = "status";
  }
}

copyAndOpenButton.addEventListener("click", async () => {
  try {
    await copyScript();
    window.open(wbUrl, "_blank", "noopener,noreferrer");
  } catch (error) {
    setError(error.message || "Не удалось скопировать скрипт.");
  }
});

copyOnlyButton.addEventListener("click", async () => {
  try {
    await copyScript();
  } catch (error) {
    setError(error.message || "Не удалось скопировать скрипт.");
  }
});

async function copyScript() {
  await navigator.clipboard.writeText(WB_EXPORT_SCRIPT);
  helperStatusEl.textContent = "Скрипт скопирован. Откройте WB, войдите, вставьте скрипт в консоль и вернитесь в mini app.";
  helperStatusEl.className = "status success";
}

function setError(message) {
  helperStatusEl.textContent = message;
  helperStatusEl.className = "status error";
}
