# WB MAX Bot

Готовый Java-сервис, который:

- заходит в отчёт Wildberries `Остатки ПМ (новая версия)`
- считывает строки таблицы `Парковка / Кол-во коробок / Кол-во КГТ / Кол-во ШК / Норма выезда ШК`
- по расписанию отправляет отчёт в `MAX`
- умеет формировать отчёт сразу по кнопке
- даёт пользователю inline-панель с настройкой интервала, порогов и номера телефона
- при превышении порогов отправляет тревогу с напоминанием или автодозвоном, если он включён
- хранит подписки, снапшоты и историю алертов в `SQLite`

## Что входит в продукт

- `Spring Boot` сервис на Java
- `SQLite` база для подписок, снапшотов и алертов
- `Playwright` scraper для личного кабинета WB
- интеграция с `MAX Bot API`
- опциональная телефония на будущее через:
  - `MANGO OFFICE` для callback-звонка в РФ
  - `Twilio` для полноценного голосового сообщения
  - `Zadarma` для callback-вызова
- `Dockerfile`
- `compose.yaml`
- `pom.xml`

## Интерфейс бота в MAX

Основной сценарий теперь кнопочный:

- `📄 Отчёт сейчас` — мгновенно снять отчёт и отправить в чат
- `👤 Аккаунты` — подключить WB-аккаунт по номеру и SMS-коду, поставить его на паузу или отключить
- `⏱️ Интервал` — выбрать автоотчёт `15 / 30 / 60 минут` или выключить его
- `🚨 Тревога` — задать порог `ШК` и порог заполнения в процентах
- `📞 Телефон` — ввести номер для дозвона или очистить его
- `☎️ Включить/выключить дозвон` — управлять автодозвоном для конкретного чата
- `ℹ️ Статус` — показать состояние WB-сессии и текущие настройки

Команды `/start`, `/report`, `/status`, `/help`, `/unsubscribe` сохранены как резервный вариант.

## Режимы MAX

Проект умеет работать в двух режимах:

- `Long Polling`
- `Webhook`

Если хотите максимально простой запуск без публичного входящего URL от `MAX`, используйте `Long Polling`.
В этом режиме бот сам опрашивает `GET /updates`.

Для `Long Polling` нужны:

- `APP_MAX_ENABLED=true`
- `APP_MAX_TOKEN=...`
- `APP_MAX_LONG_POLLING_ENABLED=true`

Для этого режима не обязательны:

- `APP_MAX_DOMAIN`
- `APP_MAX_PUBLIC_WEBHOOK_URL`
- `APP_MAX_WEBHOOK_SECRET`

## Как работает

1. Сервис по расписанию открывает страницу:
   `https://logistics.wildberries.ru/reports/remainders/last-mile/chart`
2. Считывает таблицу:
   `Маршрут`, `Парковка`, `Кол-во коробок`, `Кол-во КГТ`, `Кол-во ШК`, `Норма выезда ШК`
3. Формирует сообщение “в столбик” и отправляет его в чаты, у которых подошёл их личный интервал
4. Если строка превысила порог конкретного чата:
   - бот отправляет тревожное сообщение в `MAX`
   - при включённом дозвоне пытается позвонить на номер этого чата
   - иначе явно пишет, что нужно позвонить вручную
5. Всё сохраняется в `SQLite`

## Ограничения, которые важно понимать

- Для `MAX` нужен официальный бот. По документации MAX создание чат-ботов доступно юрлицам и ИП.
- `Long Polling` в MAX официально есть, но сама документация MAX рекомендует его для разработки и тестов, а для production рекомендует `Webhook`.
- Для входа в `WB` бот запускает `Playwright`-авторизацию и просит номер телефона и код из SMS прямо в чате.
- Если `WB` покажет дополнительную защиту, captcha или заметно поменяет форму входа, сценарий авторизации нужно будет адаптировать.
- В текущей поставке реальный обзвон по умолчанию отключён: бот работает как напоминалка для ручного звонка.
- `MANGO OFFICE` в этой реализации инициирует callback-вызов. Если нужно именно зачитывать текст голосом, потребуется отдельный IVR/роботный сценарий в MANGO.
- `Twilio` лучше подходит, если нужен именно голосовой текст в звонке.
- `Zadarma` в этой реализации делает callback-вызов, но не озвучивает кастомный текст так гибко, как `Twilio`.

## Быстрый запуск локально

### 1. Подготовьте конфиг

Скопируйте пример:

```bash
cp .env.example .env
mkdir -p data
```

Заполните минимум:

- `APP_MAX_TOKEN`
- `APP_MAX_LONG_POLLING_ENABLED=true`
- `APP_ADMIN_TOKEN`
- `APP_ALERT_VOICE_CALL_ENABLED=false`

Для текущего режима “без звонков” телефонию можно не заполнять.

### 1.1. Если позже захотите включить реальные звонки

Сейчас это не требуется. Но если позже понадобится реальный автозвонок, в этой сборке удобнее всего подключать `MANGO OFFICE` и `MAX Long Polling`.

Что взять в личном кабинете MANGO:

- `Интеграции` → `API коннектор`
- `Уникальный код вашей АТС` → `APP_TELEPHONY_MANGO_API_KEY`
- `Ключ для создания подписи` → `APP_TELEPHONY_MANGO_API_SALT`
- внутренний номер сотрудника, например `101` → `APP_TELEPHONY_MANGO_EXTENSION`
- SIP-адрес или исходящий номер сотрудника → `APP_TELEPHONY_MANGO_FROM_NUMBER`
- при необходимости номер линии АОН → `APP_TELEPHONY_MANGO_LINE_NUMBER`

Пример:

```env
APP_MAX_ENABLED=true
APP_MAX_TOKEN=replace_with_max_bot_token
APP_MAX_LONG_POLLING_ENABLED=true
APP_ADMIN_TOKEN=replace_with_admin_token

APP_ALERT_VOICE_CALL_ENABLED=true
APP_TELEPHONY_PROVIDER=mango
APP_TELEPHONY_TARGET_NUMBERS=+79990000000
APP_TELEPHONY_MANGO_API_KEY=replace_with_api_key
APP_TELEPHONY_MANGO_API_SALT=replace_with_api_salt
APP_TELEPHONY_MANGO_EXTENSION=101
APP_TELEPHONY_MANGO_FROM_NUMBER=sip:employee@example.mangosip.ru
APP_TELEPHONY_MANGO_LINE_NUMBER=
```

В режиме `Long Polling` эти переменные можно оставить пустыми:

- `APP_MAX_DOMAIN`
- `APP_MAX_PUBLIC_WEBHOOK_URL`
- `APP_MAX_WEBHOOK_SECRET`

### 2. Установите браузер Playwright

```bash
mvn -DskipTests exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

### 3. Запустите сервис

```bash
set -a
source .env
set +a
java -jar target/wb-max-bot-1.0.0.jar
```

Или сразу через Maven:

```bash
set -a
source .env
set +a
mvn spring-boot:run
```

### 4. Проверьте health-check

```bash
curl http://127.0.0.1:8080/actuator/health
```

### 5. Подпишите чат в MAX

1. Создайте и опубликуйте бота на платформе `MAX`
2. Если используете `Webhook`, настройте endpoint:
   `https://ваш-домен/webhook/max`
3. Откройте чат с ботом и нажмите `Начать`
4. Бот сам подпишет этот чат и покажет inline-панель управления
5. Откройте `👤 Аккаунты` → `🔐 Авторизовать WB`, отправьте номер телефона и затем код из SMS

Если хотите автосоздание webhook со стороны сервиса, включите:

- `APP_MAX_AUTO_REGISTER_WEBHOOK=true`

## Ручной запуск отчёта

Через MAX:

- нажмите кнопку `📄 Отчёт сейчас`
- или отправьте `/report`

Через admin endpoint:

```bash
curl -X POST \
  -H "X-Admin-Token: $APP_ADMIN_TOKEN" \
  http://127.0.0.1:8080/api/admin/run-now
```

Статус:

```bash
curl \
  -H "X-Admin-Token: $APP_ADMIN_TOKEN" \
  http://127.0.0.1:8080/api/admin/status
```

## Запуск через Docker

### 1. Соберите образ

```bash
docker build -t wb-max-bot .
```

### 2. Запустите контейнер

```bash
docker run --rm \
  --env-file .env \
  -p 8080:8080 \
  -v "$(pwd)/data:/app/data" \
  wb-max-bot
```

Или через Compose:

```bash
docker compose up -d --build
```

## Запуск на Ubuntu сервере через Docker

Ниже самый практичный вариант для production:

- `Ubuntu`
- `Docker` + `Docker Compose`
- `Caddy` для автоматического `HTTPS`
- ваш домен, например `bot.example.com`

Если на сервере уже работают другие проекты, сначала проверьте, не заняты ли `80/443`.
В таком случае безопаснее запускать только приложение на локальном порту через [compose.app-only.yaml](</Users/dmitry/Desktop/жданов 2/compose.app-only.yaml>) и подключать его к уже существующему `Nginx`/`Caddy`/`Traefik`.

Если вы хотите именно `Long Polling`, домен и webhook можно не использовать вообще.

### 1. Подготовьте сервер

Установите Docker:

```bash
sudo apt update
sudo apt install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
newgrp docker
```

### 2. Подготовьте домен

Сделайте `A` запись домена на IP вашего сервера:

- `bot.example.com -> IP_сервера`

Проверьте:

```bash
dig +short bot.example.com
```

### 3. Залейте проект на сервер

Например:

```bash
scp -r "/локальный/путь/жданов 2" user@server:/opt/wb-max-bot
ssh user@server
cd /opt/wb-max-bot
```

### 4. Создайте `.env`

```bash
cp .env.example .env
mkdir -p data
nano .env
```

Минимально заполните для `Long Polling`:

- `APP_MAX_TOKEN=...`
- `APP_MAX_LONG_POLLING_ENABLED=true`
- `APP_ADMIN_TOKEN=...`
- `APP_ALERT_VOICE_CALL_ENABLED=false`

Если позже захотите включить звонки:

- `APP_TELEPHONY_PROVIDER=mango`, `twilio` или `zadarma`
- ключи телефонии
- `APP_TELEPHONY_TARGET_NUMBERS=+79...`

Если хотите использовать `Webhook`, тогда дополнительно нужны:

- `APP_MAX_DOMAIN=bot.example.com`
- `APP_MAX_PUBLIC_WEBHOOK_URL=https://bot.example.com/webhook/max`
- `APP_MAX_WEBHOOK_SECRET=...`

### 5. Поднимите сервис

```bash
docker compose -f compose.server.yaml up -d --build
```

Проверьте:

```bash
docker compose -f compose.server.yaml ps
docker compose -f compose.server.yaml logs -f wb-max-bot
```

### 6. Проверьте health

Для `Long Polling` достаточно:

```bash
curl http://127.0.0.1:18080/actuator/health
```

Если используете режим `Webhook` с доменом, тогда проверьте ещё и `HTTPS`:

Когда `Caddy` выпустит сертификат:

```bash
curl https://bot.example.com/actuator/health
```

Должно вернуть `UP`.

### 7. Подключите webhook в MAX

Этот шаг нужен только для режима `Webhook`.

Webhook должен смотреть на:

```text
https://bot.example.com/webhook/max
```

Если включили:

- `APP_MAX_AUTO_REGISTER_WEBHOOK=true`

то сервис сам попробует зарегистрировать webhook в MAX при старте.

Если используется `Long Polling`, этот шаг пропускается.

### 8. Откройте бота в MAX

В MAX:

1. Найдите бота
2. Нажмите `Начать`
3. Откройте `👤 Аккаунты`
4. Нажмите `🔐 Авторизовать WB`
5. Отправьте номер телефона и код из SMS
6. Затем нажмите `📄 Отчёт сейчас`

### 9. Обновление сервиса

```bash
cd /opt/wb-max-bot
docker compose -f compose.server.yaml down
docker compose -f compose.server.yaml up -d --build
```

### 10. Полезные команды

Логи:

```bash
docker compose -f compose.server.yaml logs -f wb-max-bot
docker compose -f compose.server.yaml logs -f caddy
```

Перезапуск:

```bash
docker compose -f compose.server.yaml restart
```

Ручной запуск отчёта:

```bash
curl -X POST \
  -H "X-Admin-Token: ВАШ_ТОКЕН" \
  https://bot.example.com/api/admin/run-now
```

## Безопасный запуск, если на сервере уже есть другие проекты

### 1. Проверьте занятые порты

```bash
ss -ltnp | grep -E ':80 |:443 |:8080 |:18080 '
```

### 2. Если `80/443` уже заняты, не запускайте `compose.server.yaml`

Используйте только приложение:

```bash
docker compose -f compose.app-only.yaml up -d --build
```

По умолчанию сервис будет слушать:

```text
127.0.0.1:18080
```

Порт можно изменить через:

```text
APP_HOST_PORT=18080
```

### 3. Проверьте локально на сервере

```bash
curl http://127.0.0.1:18080/actuator/health
```

### 4. Дальше подключите существующий reverse proxy

Если у вас уже есть `Nginx` или `Caddy`, просто проксируйте домен на:

```text
http://127.0.0.1:18080
```

### Частые проблемы

- `бот не отвечает в MAX`
  Для `Webhook` обычно не настроен публичный `HTTPS` или неверный webhook URL. Для `Long Polling` обычно проблема в токене или в том, что пользователь не нажал `Начать`.

- `редиректит на login в WB`
  Чаще всего истекла сохранённая WB-сессия аккаунта или `WB` изменил защиту входа. Откройте `👤 Аккаунты` и пройдите авторизацию для этого номера заново.

- `напоминания приходят, но звонка нет`
  Если `APP_ALERT_VOICE_CALL_ENABLED=false`, это ожидаемо: бот только пишет, что нужно позвонить вручную.

- `звонки не идут после включения телефонии`
  Обычно неверные ключи телефонии, неподходящий номер отправителя или ограничения провайдера.

## Основные переменные окружения

- `APP_SCHEDULER_FIXED_DELAY=PT1M` — как часто сервис проверяет, не пора ли кому-то отправить отчёт
- `APP_ALERT_SHK_THRESHOLD=1200` — стартовое значение порога ШК для новых чатов
- `APP_ALERT_RATIO_THRESHOLD=0.9` — стартовое значение порога заполнения для новых чатов
- `APP_ALERT_COOLDOWN=PT30M` — защита от повторных тревог по одной и той же строке
- `APP_ALERT_VOICE_CALL_ENABLED=false` — глобально разрешить или запретить реальные звонки
- `APP_TELEPHONY_TARGET_NUMBERS=+79990000000,+79990000001` — запасной глобальный список для провайдера, если захотите использовать его отдельно от чатов

## Проверено

- проект собирается: `mvn -DskipTests package`
- приложение стартует
- `actuator/health` отвечает `UP`

## Архитектура

- [src/main/java/ru/zhdanov/wbmaxbot/service/WildberriesScraper.java](src/main/java/ru/zhdanov/wbmaxbot/service/WildberriesScraper.java)
  Снимает данные из личного кабинета WB
- [src/main/java/ru/zhdanov/wbmaxbot/service/ReportCoordinator.java](src/main/java/ru/zhdanov/wbmaxbot/service/ReportCoordinator.java)
  Оркеструет сбор, отправку сообщений и алерты
- [src/main/java/ru/zhdanov/wbmaxbot/service/MaxUpdateHandler.java](src/main/java/ru/zhdanov/wbmaxbot/service/MaxUpdateHandler.java)
  Обрабатывает команды, inline-кнопки и ввод настроек от MAX
- [src/main/java/ru/zhdanov/wbmaxbot/telephony/MangoTelephonyProvider.java](src/main/java/ru/zhdanov/wbmaxbot/telephony/MangoTelephonyProvider.java)
  Callback-вызовы через MANGO OFFICE
- [src/main/java/ru/zhdanov/wbmaxbot/telephony/TwilioTelephonyProvider.java](src/main/java/ru/zhdanov/wbmaxbot/telephony/TwilioTelephonyProvider.java)
  Голосовые звонки через Twilio
- [src/main/java/ru/zhdanov/wbmaxbot/telephony/ZadarmaTelephonyProvider.java](src/main/java/ru/zhdanov/wbmaxbot/telephony/ZadarmaTelephonyProvider.java)
  Callback-вызовы через Zadarma
