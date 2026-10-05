# AA Browser Drive

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxteneff/aabrowser-drive)
[<img src="https://img.shields.io/badge/Download_APK-GitHub_Releases-181717?style=for-the-badge&logo=github&logoColor=white" alt="Download APK" height="54">](../../releases/latest)

Форк [kododake/AABrowser](https://github.com/kododake/AABrowser) (GPLv3), который открывается в Android Auto **в том числе во время движения**.

> [!CAUTION]
> Экран предназначен для пассажиров. Водителю смотреть на него в движении нельзя.

## Чем отличается от оригинала

Оригинал проецирует на экран машины обычную Activity как «parked app», и Android Auto закрывает её, как только машина трогается. Здесь браузер зарегистрирован как приложение Car App Library категории «навигация» (`car/BrowserCarAppService`): такие приложения получают поверхность для рисования, которая остаётся доступной на ходу. На эту поверхность через виртуальный дисплей выводится тот же WebView.

Из-за этого управление на экране машины своё:

- Android Auto передаёт только тап, прокрутку, бросок и щипок, поэтому долгого нажатия и перетаскивания нет.
- Системной клавиатуры на экране машины нет, вместо неё встроенная (EN / РУ / цифры и символы). Она появляется сама при тапе в поле ввода или по кнопке `ABC`.
- Внизу панель: назад, вперёд, обновить, домой, адресная строка (тап для ввода адреса или поиска), закладки.
- Кнопка Android Auto в углу экрана прячет и возвращает панель, а также выходит из полноэкранного видео.
- На экране машины браузер сразу открывает YouTube. Другую стартовую страницу можно задать в настройках на телефоне (домашняя страница).
- Закладки, cookies и настройки общие с интерфейсом на телефоне.

Режим «parked app» из форка убран, чтобы в лаунчере машины не было двух значков. Пакет другой (`com.maxteneff.aabrowser.drive`), так что оригинал можно держать рядом.

## Установка

Нужен телефон на Android 15 или новее.

1. Установить APK из [Releases](../../releases/latest) или через Obtainium (см. ниже).
2. В настройках Android Auto на телефоне 10 раз нажать на «Версия», затем в меню ⋮ → «Для разработчиков» включить «Неизвестные источники».
3. Подключиться к машине. Если значка нет, проверить «Настроить панель запуска» в настройках Android Auto.
4. Если значок всё равно не появился: часть версий Android Auto показывает только приложения, установленные из Google Play. Тогда переустановить так:
   `adb install -r -i com.android.vending AABrowserDrive.apk`

## Установка и обновление через Obtainium

На телефоне нажать бейдж «Get it on Obtainium» вверху этой страницы (или в Obtainium выбрать «Добавить приложение» и указать `https://github.com/maxteneff/aabrowser-drive`), затем нажать «+» справа от адреса.

Тег релиза совпадает с версией приложения (`3.0.3` и т. д.), поэтому Obtainium сам видит новые версии.

## Проверка без машины

`adb shell am start -n com.maxteneff.aabrowser.drive/com.kododake.aabrowser.car.CarPreviewActivity` открывает на телефоне тот же экран, что и в машине, с тем же урезанным набором жестов (долгое нажатие заменяет кнопку Android Auto).

## Сборка

`./gradlew :app:assembleRelease`, ключ подписи задаётся в `local.properties` (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`).

## Лицензия и авторы

GPLv3, см. [LICENSE](LICENSE). Основа — [AA Browser](https://github.com/kododake/AABrowser) от kododake и участников проекта; в форке добавлен режим для экрана машины (каталог `car/`).
