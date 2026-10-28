# JuxLauncher — лаунчер Minecraft для Windows, macOS и Linux

Быстрый бесплатный лаунчер Minecraft: Java Edition. Любые версии игры, Fabric, Forge,
NeoForge и Quilt в один клик, FPS-буст и автообновление.

*Fast free Minecraft launcher for Windows, macOS and Linux with Fabric, Forge, NeoForge and Quilt support,
one-click FPS boost and auto-updates.*

### [Скачать JuxLauncher](https://juxmc.ru/launcher/)

Windows — [`JuxLauncher.msi`](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher.msi) · macOS — [Apple Silicon](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-macOS-AppleSilicon.dmg), [Intel](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-macOS-Intel.dmg) · Linux — [.deb](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-Linux.deb), [.rpm](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-Linux.rpm), [.tar.gz](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-Linux.tar.gz)

## Возможности

- **Все версии Minecraft** — релизы, снапшоты, alpha и beta, как в официальном лаунчере
- **Моды без возни** — Fabric, Quilt, Forge, NeoForge и OptiFine прямо в списке версий
- **Свои сборки** — название, иконка, копия любой версии в отдельную папку
- **Миры и бэкапы** — бэкап в один клик, автобэкап перед обновлением модов, восстановление и импорт мира
- **Разбор вылетов** — понятная причина и кнопка исправления: память, мод, зависимость, битые файлы
- **Оформление** — 4 темы, свой акцент и фон из палитры или своя картинка
- **Русский и английский** — язык выбирается в настройках
- **Каталог модов Modrinth** — поиск, установка с зависимостями и обновление модов прямо в лаунчере
- **Обновления модов сами на виду** — значок у сборки и «Обновить моды» одной кнопкой, миры перед этим сохраняются в бэкап
- **Сборка по ссылке** — «Поделиться» отдаёт другу ссылку, по ней у него соберётся та же сборка с теми же модами, шейдером и ресурспаками
- **Графика для слабого ПК** — пресеты «Слабый ПК», «Средняя» и «Высокая» в меню сборки
- **Несколько клиентов сразу** — можно открыть ещё один Minecraft, пока играешь; лаунчер при запуске прячется, сворачивается или остаётся на экране; если игра закрылась, не открывшись, лаунчер скажет почему
- **Подтверждение удаления** — аккаунты, моды и бэкапы удаляются только после «Удалить» в отдельном окне
- **FPS-буст одной кнопкой** — Sodium, Lithium, FerriteCore, EntityCulling, ImmediatelyFast и ModernFix
- **Java ставится сама** — нужная версия для каждой версии игры
- **Быстрый запуск** — повторная проверка файлов за миллисекунды, загрузка до 24 потоков
- **Сервер VirtusMine** на главном экране: онлайн и вход в один клик
- **Кнопки на [juxmc.ru](https://juxmc.ru)**: «Играть» заходит на сервер, «Установить» ставит мод, шейдер, ресурспак или сборку
- **Отдельная память и папка** для каждой сборки, миры не смешиваются
- **Перенос в новую версию** — настройки, серверы, ресурспаки и миры в один клик, в том числе из официального лаунчера и TLauncher
- **Автообновление** лаунчера

## Установка

1. Скачай [`JuxLauncher.msi`](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher.msi) — ссылка всегда ведёт на последнюю версию.
2. Запусти установщик. Если Windows покажет «Система Windows защитила ваш компьютер» —
   нажми «Подробнее» → «Выполнить в любом случае».
3. Дальше лаунчер обновляется сам.

Нужна Windows 10 или 11 (64-бит). Вход через Microsoft пока не работает — играть можно
в офлайн-режиме.

### macOS

1. Скачай DMG для своего Mac: [Apple Silicon (M1 и новее)](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-macOS-AppleSilicon.dmg)
   или [Intel](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-macOS-Intel.dmg). Какой у тебя, покажет меню Apple → «Об этом Mac».
2. Открой DMG и перетащи JuxLauncher в «Программы».
3. Лаунчер не подписан платным сертификатом Apple, поэтому первый запуск macOS остановит.
   Открой «Системные настройки» → «Конфиденциальность и безопасность» и нажми «Всё равно открыть».
   Или одной командой: `xattr -dr com.apple.quarantine /Applications/JuxLauncher.app`

Версиям Minecraft до 1.19 на Apple Silicon нужна Rosetta 2: `softwareupdate --install-rosetta`.

### Linux

- Ubuntu, Debian, Mint: [`JuxLauncher-Linux.deb`](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-Linux.deb), `sudo apt install ./JuxLauncher-Linux.deb`
- Fedora, openSUSE: [`JuxLauncher-Linux.rpm`](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-Linux.rpm), `sudo dnf install ./JuxLauncher-Linux.rpm`
- Другие: [`JuxLauncher-Linux.tar.gz`](https://github.com/AbajePlay/JuxLauncher/releases/latest/download/JuxLauncher-Linux.tar.gz), распакуй и запусти `JuxLauncher/bin/JuxLauncher`

На macOS и Linux лаунчер сам новую версию не ставит, а открывает загрузку нового файла.

## Сборка

```bash
gradlew run
gradlew packageReleaseAssets
```

`packageReleaseAssets` кладёт установщики своей системы в `build/release`. Сборки для macOS и Linux
собирает GitHub Actions (`.github/workflows/mac-linux.yml`) и сам прикладывает их к опубликованному релизу.

## Лицензия

Код JuxLauncher распространяется по [GNU GPL версии 3](LICENSE): его можно
использовать, изменять и распространять, но изменённая версия обязана выходить
под той же лицензией и с открытым исходным кодом.

Шрифт Montserrat распространяется по SIL Open Font License 1.1.

Minecraft, его файлы, текстуры и названия принадлежат Mojang Studios и под эту
лицензию не подпадают. JuxLauncher не является официальным продуктом Minecraft
и не связан с Mojang Studios и Microsoft.
