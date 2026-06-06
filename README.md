# WB MAX Bot

Готовый Java-сервис, который:

- заходит в отчёт Wildberries `Остатки ПМ (новая версия)`
- считывает строки таблицы `Парковка / Кол-во коробок / Кол-во КГТ / Кол-во ШК / Норма выезда ШК`
- по расписанию отправляет отчёт в `MAX`
- при превышении порогов запускает автозвонок через телефонию
- хранит подписки, снапшоты и историю алертов в `SQLite`

## Что входит в продукт

- `Spring Boot` сервис на Java
- `SQLite` база для подписок, снапшотов и алертов
- `Playwright` scraper для личного кабинета WB
- интеграция с `MAX Bot API`
- телефония через:
  - `Twilio` для полноценного голосового сообщения
  - `Zadarma` для callback-вызова
- `Dockerfile`
- `compose.yaml`
- `pom.xml`

## Команды бота в MAX

- `/start` или `/subscribe` — подписать текущий чат
- `/report` — мгновенно снять отчёт и отправить его в чат
- `/status` — показать статус сервиса
- `/unsubscribe` — отключить отчёты для текущего чата
- `/help` — показать справку

## Как работает

1. Сервис по расписанию открывает страницу:
   `https://logistics.wildberries.ru/reports/remainders/last-mile/chart`
2. Считывает таблицу:
   `Маршрут`, `Парковка`, `Кол-во коробок`, `Кол-во КГТ`, `Кол-во ШК`, `Норма выезда ШК`
3. Формирует сообщение “в столбик” и отправляет его в активные чаты `MAX`
4. Если строка превысила порог:
   - бот отправляет тревожное сообщение в `MAX`
   - запускается звонок на заданные номера
5. Всё сохраняется в `SQLite`

## Ограничения, которые важно понимать

- Для `MAX` нужен официальный бот. По документации MAX создание чат-ботов доступно юрлицам и ИП.
- Для получения команд и автоподписок нужен публичный `HTTPS` webhook.
- Для WB сначала нужно один раз пройти авторизацию вручную и сохранить сессию.
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
- `APP_MAX_PUBLIC_WEBHOOK_URL`
- `APP_MAX_WEBHOOK_SECRET`
- `APP_ADMIN_TOKEN`
- телефонию:
  - либо `APP_TELEPHONY_PROVIDER=twilio` и поля `APP_TELEPHONY_TWILIO_*`
  - либо `APP_TELEPHONY_PROVIDER=zadarma` и поля `APP_TELEPHONY_ZADARMA_*`

### 2. Установите браузер Playwright

```bash
mvn -DskipTests exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

### 3. Один раз сохраните сессию WB

Это обязательный шаг. Сервис откроет браузер, вы вручную входите в WB, после чего файл сессии сохранится автоматически.

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--app.mode=bootstrap-wb-session --app.wildberries.headless=false"
```

После успешного входа появится файл:

`./data/wb-storage-state.json`

### 4. Запустите сервис

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

### 5. Проверьте health-check

```bash
curl http://127.0.0.1:8080/actuator/health
```

### 6. Подпишите чат в MAX

1. Создайте и опубликуйте бота на платформе `MAX`
2. Настройте webhook на:
   `https://ваш-домен/webhook/max`
3. Откройте чат с ботом и нажмите `Начать`
4. Бот сам подпишет этот чат

Если хотите автосоздание webhook со стороны сервиса, включите:

- `APP_MAX_AUTO_REGISTER_WEBHOOK=true`

## Ручной запуск отчёта

Через MAX:

- отправьте боту `/report`

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

### 1. Сначала локально создайте файл сессии WB

Нужно выполнить шаг `bootstrap-wb-session` локально, чтобы в `./data` появился файл:

- `wb-storage-state.json`

### 2. Соберите образ

```bash
docker build -t wb-max-bot .
```

### 3. Запустите контейнер

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

Минимально заполните:

- `APP_MAX_DOMAIN=bot.example.com`
- `APP_MAX_PUBLIC_WEBHOOK_URL=https://bot.example.com/webhook/max`
- `APP_MAX_TOKEN=...`
- `APP_MAX_WEBHOOK_SECRET=...`
- `APP_ADMIN_TOKEN=...`
- `APP_TELEPHONY_PROVIDER=twilio` или `zadarma`
- ключи телефонии
- `APP_TELEPHONY_TARGET_NUMBERS=+79...`

### 5. Очень важный шаг: файл сессии WB

На сервере без GUI неудобно проходить вход в WB. Поэтому проще так:

1. На своём компьютере локально выполните bootstrap:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--app.mode=bootstrap-wb-session --app.wildberries.headless=false"
```

2. После успешного входа появится файл:

```bash
data/wb-storage-state.json
```

3. Скопируйте его на сервер в папку проекта:

```bash
scp data/wb-storage-state.json user@server:/opt/wb-max-bot/data/
```

Без этого файла контейнер не сможет зайти в кабинет WB.

### 6. Поднимите сервис

```bash
docker compose -f compose.server.yaml up -d --build
```

Проверьте:

```bash
docker compose -f compose.server.yaml ps
docker compose -f compose.server.yaml logs -f wb-max-bot
```

### 7. Проверьте HTTPS и health

Когда `Caddy` выпустит сертификат:

```bash
curl https://bot.example.com/actuator/health
```

Должно вернуть `UP`.

### 8. Подключите webhook в MAX

Webhook должен смотреть на:

```text
https://bot.example.com/webhook/max
```

Если включили:

- `APP_MAX_AUTO_REGISTER_WEBHOOK=true`

то сервис сам попробует зарегистрировать webhook в MAX при старте.

### 9. Откройте бота в MAX

В MAX:

1. Найдите бота
2. Нажмите `Начать`
3. Отправьте `/status`
4. Затем `/report`

### 10. Обновление сервиса

```bash
cd /opt/wb-max-bot
docker compose -f compose.server.yaml down
docker compose -f compose.server.yaml up -d --build
```

### 11. Полезные команды

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

### Частые проблемы

- `бот не отвечает в MAX`
  Обычно не настроен публичный `HTTPS` или неверный webhook URL.

- `редиректит на login в WB`
  Значит устарел файл `data/wb-storage-state.json`. Нужно заново пройти bootstrap локально и загрузить новый файл на сервер.

- `звонки не идут`
  Обычно неверные ключи телефонии, неподходящий номер отправителя или ограничения провайдера.

## Основные переменные окружения

- `APP_SCHEDULER_FIXED_DELAY=PT10M` — как часто отправлять отчёт
- `APP_ALERT_SHK_THRESHOLD=1200` — порог по ШК
- `APP_ALERT_RATIO_THRESHOLD=0.9` — порог по заполнению нормы
- `APP_ALERT_COOLDOWN=PT30M` — защита от повторных звонков по одной и той же строке
- `APP_TELEPHONY_TARGET_NUMBERS=+79990000000,+79990000001` — номера для обзвона

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
  Обрабатывает команды от MAX
- [src/main/java/ru/zhdanov/wbmaxbot/telephony/TwilioTelephonyProvider.java](src/main/java/ru/zhdanov/wbmaxbot/telephony/TwilioTelephonyProvider.java)
  Голосовые звонки через Twilio
- [src/main/java/ru/zhdanov/wbmaxbot/telephony/ZadarmaTelephonyProvider.java](src/main/java/ru/zhdanov/wbmaxbot/telephony/ZadarmaTelephonyProvider.java)
  Callback-вызовы через Zadarma
