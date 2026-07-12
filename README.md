# VpnClient — Android VPN-клиент на базе sing-box

## Статус проекта

| Компонент | Готовность |
|---|---|
| Парсер ссылок (vless/vmess/trojan/ss) | ✅ Готов |
| Парсер подписок (URL + Base64) | ✅ Готов |
| UI (список серверов, кнопка подключения) | ✅ Готов |
| VPN-сервис (CoreVpnService) | ✅ Готов |
| ConfigBuilder (sing-box JSON) | ✅ Готов |
| Движок libbox.aar | ⚠️ Нужно скачать вручную (см. ниже) |

---

## ШАГ 1 — Скачай libbox.aar (ОБЯЗАТЕЛЬНО перед сборкой)

Без этого файла сборка упадёт с ошибкой `FileNotFoundException: libbox.aar`.

1. Открой: https://github.com/SagerNet/sing-box-for-android/releases
2. Найди последний релиз (тег вида `1.x.x`)
3. В разделе **Assets** скачай файл **`libbox.aar`**
4. Положи его в папку `app/libs/` внутри проекта:
   ```
   VpnClient/
   └── app/
       └── libs/
           └── libbox.aar   ← сюда
   ```

---

## ШАГ 2 — Сборка APK

Открой терминал в папке VpnClient и запусти:

```bash
./gradlew assembleDebug
```

APK появится по адресу: `app/build/outputs/apk/debug/app-debug.apk`

---

## Возможные ошибки при первой сборке

### "class CoreVpnService is not abstract and does not implement abstract member..."
Это значит, что версия libbox.aar, которую ты скачал, ожидает дополнительные методы
в PlatformInterface по сравнению с тем, что реализовано.

Решение: открой `CoreVpnService.kt` в Android Studio, нажми Alt+Enter на имя класса
и выбери "Implement members" — студия добавит заглушки. Затем пришли
список добавленных методов — допишем их логику.

### "Could not resolve..." / "Failed to resolve..."
Значит libbox.aar не лежит в `app/libs/`. Проверь путь ещё раз.

### "SDK location not found"
Нужно установить Android Studio: https://developer.android.com/studio
После установки повтори `./gradlew assembleDebug`.

---

## Архитектура

```
MainActivity (Compose UI)
    ↓ startService / stopService
CoreVpnService (android.net.VpnService + PlatformInterface)
    ↓ Libbox.newService(configJson, this)
libbox.aar (движок sing-box / Go)
    ↓ openTun() callback → Builder.establish()
Android TUN-интерфейс
```

Состояние подключения передаётся через `VpnStatus` (StateFlow object).

---

## Следующие шаги после успешной сборки

1. Протестировать подключение с реальным vless/vmess/trojan/ss сервером
2. Добавить сохранение списка серверов (SharedPreferences или Room)
3. Добавить виджет/быстрые настройки для подключения из шторки
4. Подписать APK для публикации (release build)
