# TrafficSignalStudio Mobile

Отдельное Android-приложение в составе репозитория TrafficSignalStudio. Существующий Windows/Python-прототип не изменяется.

## Цели первой итерации

- Android 10+ (minSdk 29), Kotlin и Jetpack Compose.
- Гибридный редактор: библиотека шаблонов + дальнейшее ручное редактирование.
- Локальные проекты в формате TSS/JSON, автономная работа.
- Отдельные модули модели, геометрии, хранения и редактора.
- Собственное расчётное и микросимуляционное ядро; SUMO не является обязательной зависимостью.

## Сборка

Требуются Android Studio с JDK 17 и Android SDK Platform 37.

После открытия каталога `mobile/` в Android Studio выполните:

```bash
gradle :core:model:test
gradle :core:geometry:test
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

APK ожидается по пути `app/build/outputs/apk/debug/app-debug.apk`.

Gradle Wrapper JAR и скрипты пока не включены. Для локальной сборки можно открыть проект в Android Studio либо сгенерировать wrapper командой `gradle wrapper --gradle-version 9.7.0`.

## Модули

- `:app` — приложение, навигация и главное меню.
- `:core:model` — независимая доменная модель проекта.
- `:core:geometry` — геометрия и шаблоны перекрёстков.
- `:data:local` — локальное JSON-хранилище.
- `:feature:editor` — Compose-экран схемы перекрёстка.

Дальнейшие модули движка (конфликты, сигналы, безопасность, микросимуляция, координация, оптимизация и аналитика) добавляются отдельными итерациями, не связывая UI с вычислительной реализацией.
