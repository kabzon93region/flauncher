# FLauncher
FLauncher is an open-source alternative launcher for Android TV, built with [Flutter](https://flutter.dev).

The project is still at an early development stage and may be unstable. It currently lacks testing on real devices and has only been tested on Chromecast with Google TV.

<a href="https://play.google.com/store/apps/details?id=me.efesser.flauncher">
 <img alt="Get it on Google Play" width="200" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"/>
</a>

## Features
- [x] No ads
- [x] Customizable categories
- [x] Manually reorder apps within categories
- [x] Wallpaper support
- [x] Open "Android Settings"
- [x] Open "App info"
- [x] Uninstall app
- [x] Clock
- [x] Switch between row and grid for categories
- [x] Support for non-TV (sideloaded) apps
- [x] Navigation sound feedback
- [ ] Force stop app

## Screenshots
|  |  |  |
|--|--|--|
| ![](screenshots/Screenshot_1624378896.png) | ![](screenshots/Screenshot_1624378921.png) | ![](screenshots/Screenshot_1624378938.png) |

## Set FLauncher as default launcher

### Method 1: remap the Home button
This is the "safer" and easiest way. Use [Button Mapper](https://play.google.com/store/apps/details?id=flar2.homebutton) to remap the Home button of the remote to launch FLauncher.

### Method 2: disable the default launcher
**:warning: Disclaimer :warning:**

**You are doing this at your own risk, and you'll be responsible in any case of malfunction on your device.**

The following commands have been tested on Chromecast with Google TV only. This may be different on other devices.

Once the default launcher is disabled, press the Home button on the remote, and you'll be prompted by the system to choose which app to set as default.

#### Disable default launcher
```shell
# Disable com.google.android.apps.tv.launcherx which is the default launcher on CCwGTV
$ adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
# com.google.android.tungsten.setupwraith will then be used as a 'fallback' and will automatically
# re-enable the default launcher, so disable it as well
$ adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

#### Re-enable default launcher
```shell
$ adb shell pm enable com.google.android.apps.tv.launcherx
$ adb shell pm enable com.google.android.tungsten.setupwraith
```

#### Known issues
On Chromecast with Google TV (maybe others), the "YouTube" remote button will stop working if the default launcher is disabled. As a workaround, you can use [Button Mapper](https://play.google.com/store/apps/details?id=flar2.homebutton) to remap it correctly.

## Модификации этой форки (TV-приставка VONTAR X4)

Эта форка содержит доработки для домашней Android TV-приставки (VONTAR X4,
Android 11, root через Magisk). Оригинал: [GitLab flauncher/flauncher](https://gitlab.com/flauncher/flauncher).

### Что изменено

- **Кнопка-ракета 🚀** рядом со шестерёнкой настроек — очистка оперативной
  памяти. Снекбар показывает результат: `Закрыто фоновых приложений: N ·
  освобождено X МБ · свободно Y МБ`.
  - В цель попадают **только запущенные фоновые процессы сторонних
    приложений**: системные пакеты не трогаются вообще (отбор по
    `pm list packages -3` / фильтр `FLAG_SYSTEM`);
  - **лаунчер и Magisk явно исключены** из списка целей; AMS дополнительно
    не даёт убить foreground/foreground-service;
  - основной путь — через `su` (разрешение выдано в Magisk DB policies,
    без промптов), запасной путь без рута — `killBackgroundProcesses`;
  - цикл очистки выполняется в **фоновом потоке**, интерфейс не замирает.
- **Удалён Firebase** (core/analytics/crashlytics/remote config), связка
  `google-services`/`crashlytics-gradle` в Gradle и устаревшие тесты,
  покрывавшие его. Логика не зависела от Firebase: Unsplash-ключи
  обнулены, тумблеры отчётов/аналитики убраны.
- **`device-config/flauncher-layout.json`** — экспорт раскладки главного
  экрана (категории, приложения, порядок); см.
  [device-config/README.md](device-config/README.md). Три пакета
  вырезаны из экспорта по решению владельца.

### Сборка

Требуется **Flutter 3.7.5** (точный пин в `pubspec.yaml`, Dart 2.19),
JDK 17, Android SDK с платформой `android-33`.

```shell
flutter pub get
flutter build apk --release
# результат: build/app/outputs/flutter-apk/app-release.apk
```

Release подписывается **debug-ключом** машины сборщика (см.
`android/app/build.gradle`), поэтому на устройстве со старой подписью —
сначала `adb uninstall me.efesser.flauncher`, затем установка; конфиг
предварительно выгрузить (`app_flutter/db.sqlite`) и залить обратно.

### Установка на приставку

```shell
adb install -r build/app/outputs/flutter-apk/app-release.apk
adb shell cmd package set-home-activity me.efesser.flauncher/.MainActivity
```

Если нужен root-путь ракеты — выдать Magisk разрешение (uid подставить
свой): `INSERT INTO policies VALUES(<uid>, 'me.efesser.flauncher', 2, 0, 0, 0)`
через `magisk --sqlite`.

---

## Wallpaper
Because Android's `WallpaperManager` is not available on some Android TV devices, FLauncher implements its own wallpaper management method.

Please note that changing wallpaper requires a file explorer to be installed on the device in order to pick a file.

<a href="https://www.buymeacoffee.com/etienn01" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="200"></a>
