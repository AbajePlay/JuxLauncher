# JuxLauncher — лаунчер Minecraft для Windows

Быстрый бесплатный лаунчер Minecraft: Java Edition. Любые версии игры, Fabric, Forge,
NeoForge и Quilt в один клик, FPS-буст и автообновление.

*Fast free Minecraft launcher for Windows with Fabric, Forge, NeoForge and Quilt support,
one-click FPS boost and auto-updates.*

### [Скачать JuxLauncher для Windows](https://github.com/AbajePlay/JuxLauncher/releases/latest)

## Возможности

- **Все версии Minecraft** — релизы, снапшоты, alpha и beta, как в официальном лаунчере
- **Моды без возни** — Fabric, Quilt, Forge и NeoForge прямо в списке версий
- **Каталог модов Modrinth** — поиск, установка с зависимостями и обновление модов прямо в лаунчере
- **FPS-буст одной кнопкой** — Sodium, Lithium, FerriteCore, EntityCulling, ImmediatelyFast и ModernFix
- **Java ставится сама** — нужная версия для каждой версии игры
- **Быстрый запуск** — повторная проверка файлов за миллисекунды, загрузка до 24 потоков
- **Сервер VirtusMine** на главном экране: онлайн и вход в один клик
- **Отдельная память и папка** для каждой сборки, миры не смешиваются
- **Автообновление** лаунчера

## Установка

1. Скачай `JuxLauncher-x.x.x.msi` на [странице релизов](https://github.com/AbajePlay/JuxLauncher/releases/latest).
2. Запусти установщик. Если Windows покажет «Система Windows защитила ваш компьютер» —
   нажми «Подробнее» → «Выполнить в любом случае».
3. Дальше лаунчер обновляется сам.

Нужна Windows 10 или 11 (64-бит). Вход через Microsoft пока не работает — играть можно
в офлайн-режиме.

## Сборка

```bash
gradlew run
gradlew packageReleaseDistributionForCurrentOS
```

## Лицензия

Код JuxLauncher распространяется по [GNU GPL версии 3](LICENSE): его можно
использовать, изменять и распространять, но изменённая версия обязана выходить
под той же лицензией и с открытым исходным кодом.

Шрифт Montserrat распространяется по SIL Open Font License 1.1.

Minecraft, его файлы, текстуры и названия принадлежат Mojang Studios и под эту
лицензию не подпадают. JuxLauncher не является официальным продуктом Minecraft
и не связан с Mojang Studios и Microsoft.
