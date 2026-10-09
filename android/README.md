# DustoreX для Android

Приложение dustore.ru для телефона: магазин, библиотека, аккаунт eXepc, связь с
компьютером (eXepad, eXeportal, перевод игр) и **eXen** — система, которая запускает игры для
Windows прямо на телефоне, без компьютера.

Выделенная общая копия **Free / Prime 5.19 (code31)**: lifecycle cleanup ввода,
сохранённые отменяемые операции eX, background metadata и reuse карточек, native
accessibility пультов, крупный шрифт и измеренное сокращение собственных Java allocations.
Package `ru.dustore.launcher`, min24/target34, ARM64 и исходный сертификат сохранены.
Сборки обеих редакций выполняются последовательно одним проектом; существующие ключи
лаунчера и eX-шаблона сохраняются. Прежние Free 15s / 2MiB/s / 3 успешных переноса в день
проверены отдельно. Runtime QA этой итерации — изолированный API34 эмулятор, без замера
FPS настоящей игры/физического ARM64 устройства. Старые сведения о конкретном телефоне
ниже являются исходными техническими заметками проекта.

```
.\Dustore.apk / .\Dustore-Prime.apk                   файлы после сборки, версия 5.19
powershell -ExecutionPolicy Bypass -File build.ps1 -Edition Free
powershell -ExecutionPolicy Bypass -File build.ps1 -Edition Prime
powershell -ExecutionPolicy Bypass -File tests\run-tests.ps1 recipe "D:\Games\..."   проверки на JDK
```

Собирается без Gradle и без единой сторонней библиотеки. Тесты на ПК используют
`tests\lib\json.jar` (эталонный org.json, тот же, что встроен в Android) — только потому,
что в android.jar от него одни заглушки.

## eX — игры для Windows на телефоне

Путь человека без компьютера: скачать игру для Windows в магазине → в библиотеке
**«Эмулировать · eX»** → дождаться семи шагов → **«Установить APK игры»** → на рабочем
столе значок с иконкой игры, запускается офлайн, как любая другая.

Наше, написанное здесь: экран (композитор Wayland на Java, `ExDisplay`), ввод и пады
(`PadView`, `ExPads`), загрузчик частей с докачкой (`ExParts`), сборщик системы (`ExBuild`),
разбор игры (`ExRecipe`), переукладка программ под правила Android (`ExPe`), иконка из .exe
(`ExIcon`), сборщик и подписыватель APK (`ExApk`, `ExAxml`), конвейер эмуляции
(`ExConvert`, `ExProbe`), якорь от заморозки (`ExService`).

Основной путь запуска: **Hangover 11.16** с ARM64 Wine, переводом кода Windows-игры через
FEX/wowbox64, **Mesa Turnip** для Adreno и входящим в архив Hangover **DXVK 2.7.1**.
Старый Box64 + Wine amd64 остаётся запасным путём, если ARM64 Wine не установился.
При подготовке новой игры система сначала ставит Hangover и пробует игру в нём.
Для Unity-игр eX использует 640×360 и передаёт Player параметры низкого качества;
остальные игры остаются на 800×450. Это мера для FPS, а не обещание минимальных 30 к/с.

### Что телефон качает сам

| Часть | Откуда | Размер |
| --- | --- | --- |
| основа Ubuntu 24.04 arm64 | cdimage.ubuntu.com | 30 МБ |
| Hangover 11.16 ARM64 с DXVK 2.7.1 | github AndreRH/hangover | 307 МБ |
| Mesa Turnip (KGSL) | github lfdevs/mesa-for-android-container | 11 МБ |
| пакеты (libwayland 1.23, Vulkan-loader, vulkan-tools, шрифты, части x86_64) | apt внутри системы | ~100 МБ |

Wine amd64 (96 МБ) и DXVK 3.1 (17 МБ) загружаются лишь для запасного пути.

Компьютер по кабелю может докинуть те же файлы в `files/parts/` — ускоритель, не условие.
Зеркало для частей задаётся строкой в настройках (`SharedPreferences "ex"`, ключ `mirror`).

### Ступени системы (`Ex.stage`)

Основной путь: основа → пакеты → видеокарта (`.ex-gpu`) → ARM64 Wine с DXVK
(`.ex-hangover-11.16`). Состояние проверяется по файлам и профилю Wine. Для старой
системы по-прежнему распознаются ступени Wine amd64 и `.ex-dxvk`.

### Как игра видит видеокарту

ARM64 Wine → winevulkan → Vulkan-loader → `VK_DRIVER_FILES` = Turnip →
`/dev/kgsl-3d0`. Кадры отдаются через общую память (`MESA_VK_WSI_DEBUG=sw`) в
композитор `ExDisplay`. На SM-A366E `vulkaninfo` называет `Turnip Adreno (TM) 710`.

Godot 4 получает ключи `--rendering-method mobile --fullscreen`: процессор здесь узкое
место (код Godot идёт через переводчик, 350 % CPU на тяжёлой сцене), и лёгкая рисовалка
снимает с него половину работы.

### Стаб-APK

Итог эмуляции — крошечный APK (`res/raw/exstub.bin` — шаблон, собирается из `exstub/`):
телефон подменяет в бинарном манифесте имя пакета `ru.exepc.exgame.<id>` и подпись, кладёт
иконку игры и подписывает файл схемой v2 своим кодом ключом из `res/raw/exstub_key.bin`.
По нажатию значка стаб зовёт `ExActivity` с действием `ru.exepc.ex.PLAY`. Файлы игры
лежат в одном месте — во внутренней памяти DustoreX (`files/ex/games/<id>`), стаб их не
дублирует. Копия APK кладётся в `Download/DustoreX/<Игра>.apk`.

### Ловушки, стоившие времени

- **Заморозка в фоне.** Samsung (Freecess) замораживает приложение через 5 с после ухода
  в фон, и поток эмуляции встаёт. Лечится foreground-сервисом с уведомлением
  (`ExService.hold`), не просьбой держать экран.
- **Проба в фоне убивает всё.** Игра в пробе занимает 1,5 ГБ; свёрнутое приложение при
  нехватке памяти убивают первым (проверено: SIGKILL на тридцатой секунде). Проба идёт
  только на переднем плане, иначе честно помечается пропущенной.
- **Assets невидимы.** `getAssets().list("ex")` вернул пустой список, хотя файлы лежали в
  APK. Шаблон стаба и ключ живут в `res/raw` — `openRawResource` не подводит.
- **d8.bat и 8191 знак.** Список классов перестал влезать в командную строку cmd.exe —
  классы упаковываются в jar и отдаются одним файлом.
- **Подпись v2.** Для дайджеста поле «смещение каталога» в EOCD должно указывать на
  начало блока подписи (старое смещение каталога), в файле — на новое.
- **resources.arsc** обязан быть без сжатия и по границе 4 байт: лишнее поле `0xD935`,
  как у zipalign.
- **PowerShell без BOM** читает .ps1 как ANSI: типографское тире внутри строки ломает
  весь скрипт. Скрипты сборки — ASCII.

## Остальное

| Экран | Что делает |
| --- | --- |
| Магазин | сайт во встроенном браузере, загрузки перехватываются |
| Библиотека | что скачано: установка, запуск, эмуляция через eX, сколько сыграно |
| Отзывы | оценки всех игр, свои и чужие, вход в аккаунт eXepc |
| С компьютера | eXepad (телефон-джойстик, выбор раскладки плитками с превью), карточка eX, игры компьютера, eXeportal |

Оценить можно только игру, скачанную через приложение и в которую хотя бы раз заходил — то
же правило, что в компьютерном лаунчере. Скачанное опознаётся по содержимому: `AndroidManifest.xml`
внутри → приложение; `index.html` → веб-сборка (свой сервер на 127.0.0.1 с заголовками
Cross-Origin-*-Policy); `.exe`/`.dll` в zip → сборка для Windows, ей и нужен eX.

## Разрешения

`INTERNET`, `ACCESS_NETWORK_STATE` — сеть. `REQUEST_INSTALL_PACKAGES` — право попросить
установить игру или её стаб. `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`POST_NOTIFICATIONS` — якорь от заморозки на время сборки и эмуляции. `PACKAGE_USAGE_STATS`
— время в игре (включается человеком в настройках). Блок `<queries>` — только приложения со
значком и стабы игр eX.

## Отладка по кабелю

```
adb shell am start -n ru.dustore.launcher/.MainActivity -f 0x10000000 --es ex install
adb shell am start -n ru.dustore.launcher/.MainActivity -f 0x10000000 --es ex parts
adb shell am start -n ru.dustore.launcher/.MainActivity -f 0x10000000 --es ex verify
adb shell am start -n ru.dustore.launcher/.MainActivity -f 0x10000000 --es ex convert --es zip <файл в files/Download>
adb shell am start -n ru.dustore.launcher/.MainActivity -f 0x10000000 --es ex convert --es folder <папка с dustorex.json>
adb shell touch /sdcard/Android/data/ru.dustore.launcher/files/ex.debug   # открыть дверь
adb shell am start -n ru.dustore.launcher/.MainActivity -f 0x10000000 --es ex sh --es cmd "vulkaninfo --summary | head"
adb shell cat /sdcard/Android/data/ru.dustore.launcher/files/ex.log
```

Журнал — `files/ex.log`, состояние системы — `files/ex.state`; оба видны кабелю.
