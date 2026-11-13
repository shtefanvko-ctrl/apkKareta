# KARETA.KZ Android APK — открытые работы на 2026-11-13

Базовая ветка: `main`, Android shell `1.4.5 (11)`, `kz.kareta.app`.
Коммит смены домена: `0e0259a0f59ca7adbc3055d26df609c9e8439f1d`.
Основной адрес и единственный доверенный Native API origin: `https://kareta.kz/`.
Не смешивать версию Android APK с версией веб-ресурсов.

| Приоритет | Состояние | Работа | Фактическое основание |
| --- | --- | --- | --- |
| P0 | BLOCKED external | Доступность production origin, включая HTTPS, Cloudflare/туннель и старт SPA | 2026-11-13 внешнее HTTP-чтение `https://kareta.kz/` получало 530; это не доказательство стабильного состояния сервера |
| P0 | NOT DONE | Подписанный production-release с постоянным ключом, хранение ключа вне Git, воспроизводимая сборка, безопасное обновление | `main` CI собирает только debug; в Gradle нет release signingConfig; PR #7 содержит только staging PKCS12 подпись |
| P0 | NOT RUN | Установка/обновление на физическом Android, вход для ролей, камера, QR, геолокация, ELM327, возврат из системного экрана, WebView restart | CI lint/assemble не заменяют физические E2E |
| P1 | STUB | Push: FCM token, registration/unregistration, доставка и переход по уведомлению | `MainActivity.dispatch()`: `pushToken` returns empty token/configured=false; `registerPush`/`unregisterPush` returns configured=false |
| P1 | LIMITED | Офлайн-очередь: гарантировать отсутствие потерь и защиту локальных данных | `OfflineQueue.MAX_ITEMS=100`; `enqueue` и `restore` тихо удаляют самые старые записи при переполнении; SharedPreferences без явного шифрования |
| P1 | DRAFT | Стабильный installationId для мобильных OBD-задач и согласование с сервером | APK PR #2 не объединён; отсутствуют FCM, background ELM jobs/foreground service |
| P1 | LIMITED | Диагностика транспортов ELM327 | `main` только Bluetooth Classic SPP. BLE UART и Wi-Fi TCP реализованы лишь в staging PR #8; физические проверки ECU ещё не подтверждены |
| P2 | DRAFT | Распознавание техпаспорта (Native API 7, ML Kit) | APK PR #10 и веб-пара PR #133 ещё не объединены; казахский/кириллица и физические документы не проверены |
| P2 | NOT DONE | USB ELM327 OTG/CDC/CH340/FTDI/CP2102, OEM ABS/SRS | PR #8 явно исключает USB и OEM диагностику |
| P2 | REVIEW | Минимизация разрешений Android | `MainActivity.pickContact` использует ACTION_PICK, а основной manifest всё ещё запрашивает READ_CONTACTS; PR #1 по минимизации не объединён |
| P2 | REVIEW | Финальная иконка, наименование, splash и соответствие брендбуку | Основная ветка использует label KARETA.KZ; изменения имени есть лишь в staging PR #7 |

## Границы версии 1.4.5

Выполнена смена WebView/Native API на `kareta.kz`. Не переносились незавершённые staging-функции, не менялись PHP/API/БД, не выполнялся релиз в Google Play. Веб-приложение `js/mobile_native_bridge.js` содержит совместимые имена команд Native API 6. Переключение origin требует повторного входа пользователей, ранее авторизованных на `s.kareta.kz`.

## Минимальный путь к production APK

1. Подтвердить, что `https://kareta.kz/` отдаёт стартовый HTML без HTTP 530, и проверить Native bridge на живом сайте.
2. Завершить lint + assembleDebug CI точного SHA 1.4.5, протестировать на устройстве.
3. Настроить отдельный постоянный production signing key с безопасным хранением и проверить путь обновления установленных APK (debug-ключи могут различаться).
4. Закрыть P1 push/offline/data-safety и принять решение о переносе ELM BLE/Wi-Fi и OCR только с их совместимыми веб-изменениями.
5. Сформировать подписанный release APK и опубликовать только проверенный артефакт.
