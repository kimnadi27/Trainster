# Размещение Trainster на Debian 13 без домена

Схема: Android → HTTPS на публичном IPv4 → Nginx → FastAPI на127.0.0.1:8000. Один Uvicorn worker сохраняет действующие ограничения публичных геосервисов на один процесс. SQLite находится отдельно от кода. Systemd запускает API после перезагрузки.

Подготовленный install.sh предназначен для VPS со свободными TCP80,443,8000. При занятых портах он остановится до установки: существующий сайт, панель или VPN нужно учитывать в конфигурации, не останавливать вслепую. Текущий скрипт принимает публичный IPv4, не IPv6.

[Let's Encrypt выдаёт сертификаты IP](https://letsencrypt.org/2026/03/11/shorter-certs-certbot). Нужен Certbot5.4+, профиль shortlived, сертификат действует около6дней. Скрипт создаёт отдельное окружение Certbot и systemd-таймер продления дважды в сутки. Согласие с условиями Let's Encrypt передаётся при запуске установщика, без регистрации email. Скрипт нужно запускать на своём сервере вручную; из среды разработки удалённая установка не выполнялась.

## 1. Подготовить архив на Windows

Сначала в Android нажмите «Сохранить» на старом сервере. Неопубликованный черновик можно сохранить без публикации. Изменения, оставшиеся только на телефоне, в SQLite ещё не попали.

В PowerShell:

```powershell
cd D:\Trainster
.\server\.venv\Scripts\python.exe .\scripts\package-server.py
```

Архив: artifacts/deploy/trainster-server.tar.gz. В него входят Python-код, зависимости, файлы установки и согласованный SQLite-снимок аккаунтов/поездок через SQLite backup API (учитывается WAL). Живой файл базы открыт только на чтение. Пароль аккаунта не меняется. Архив содержит данные пользователей — не размещайте его в открытом доступе.

По умолчанию используется server/data/trainster.sqlite3. Если сервер запущен с другим TRAINSTER_DATABASE, передайте его фактический путь через --database. Для нового пустого сервера можно передать --without-data.

Ключ API и .env в архив не входят. Их не нужно отправлять в чат.

## 2. Отправить архив через SSH

Замените PUBLIC_IP на настоящий публичный IPv4. В примере вход под root; если используете другой SSH-логин, замените root на него, а установщик запускайте с sudo. Для нестандартного SSH-порта используйте scp -P PORT и ssh -p PORT.

```powershell
scp .\artifacts\deploy\trainster-server.tar.gz root@PUBLIC_IP:~/
ssh root@PUBLIC_IP
```

В панели хостинга и существующем firewall разрешите входящие TCP80 и443, сохранив доступ к своему SSH-порту.8000 наружу открывать не нужно. Установщик не меняет firewall и не выключает другие службы.

## 3. Установить на VPS

Следующие команды выполняются в SSH-терминале Debian, от root:

```bash
chmod 600 ~/trainster-server.tar.gz
mkdir -p ~/trainster-release
tar -xzf ~/trainster-server.tar.gz -C ~/trainster-release
cd ~/trainster-release
bash deploy/debian/install.sh PUBLIC_IP
```

Установщик использует apt для Python/Nginx, создаёт отдельного системного пользователя trainster, виртуальные окружения и службу. При первом запуске переносит снимок SQLite, если он включён. При повторном запуске существующая база не заменяется; создаётся резервная копия в/var/backups/trainster. Существующий server.env тоже сохраняется.

Чтобы включить расписания:

```bash
nano /etc/trainster/server.env
```

Впишите ваш прежний ключ в YANDEX_RASP_API_KEY= и сохраните Ctrl+O, Enter, Ctrl+X. Затем:

```bash
systemctl restart trainster
curl --fail https://PUBLIC_IP/health
/opt/trainster-certbot/bin/certbot renew --cert-name trainster-ip --dry-run
systemctl list-timers trainster-certbot.timer
```

Ответ health должен быть {"status":"ok"}. Не отключайте проверку сертификата curl: у HTTPS должен быть общедоверенный сертификат IP. Ошибки dry-run нужно устранить: короткоживущий сертификат требует работающего автоматического продления.

## 4. Подключить приложение

В настройках Android-приложения сохраните адрес https://PUBLIC_IP без порта8000 и без/api/v1. Войдите старым логином и паролем, если переносили SQLite. Смена сервера завершает текущую сессию; локальные копии разделены по серверу и аккаунту. Загрузите поездку, проверьте поиск рейсов и скачайте нужные карты до дороги.

Новый адрес должен использовать каждый участник. APK пересобирать не нужно. Удалённый сервер должен быть включён и доступен телефонам; настройки фоновой доставки уведомлений не меняются.

## Обслуживание

```bash
systemctl status trainster --no-pager
journalctl -u trainster -n 100 --no-pager
journalctl -u trainster-certbot -n 50 --no-pager
nginx -t
systemctl restart trainster
```

Постоянные данные: /var/lib/trainster/trainster.sqlite3 и кеш железнодорожных линий рядом. Конфигурация: /etc/trainster/server.env, код: /opt/trainster/server. Сервис записывает данные только в/var/lib/trainster. Резервную копию работающей базы создавайте через SQLite backup API или sqlite3 .backup, а не копированием одного файла без WAL.

Если сертификат не выпускается, проверьте IP, входящий TCP80 и доступ к нему из интернета. Если порты заняты, пришлите вывод ss -ltnp для80/443/8000 без паролей: конфигурацию нужно совместить с существующими сервисами.

Источник по TLS/продлению: [Let's Encrypt IP certificates](https://letsencrypt.org/2026/03/11/shorter-certs-certbot); по прокси — [Uvicorn deployment](https://www.uvicorn.org/deployment/).
