# REGIN V3 Android

Aplikacja Bluetooth do Regina V3 (line follower z turbina). Komendy sa te same co w GRUZIK4.0, firmware obsluguje je w `Core/Src/SimpleParser.c`.

## Gotowy APK

`apk/REGIN-V3-debug.apk`

Build debug, mozna go zainstalowac obok aplikacji GRUZIK4.0 (inny `applicationId`).

## Polaczenie

1. Sparuj modul HC-04 w ustawieniach Bluetooth Androida.
2. Wybierz go z listy i nacisnij `Connect`.

## Zakladki

- `Drive` - turbina (predkosc 0-1000, czas rozpedzania w ms), PID, predkosci, presety, czyszczenie opon. `Start` wysyla wszystkie wartosci i startuje robota, `Stop` jest zawsze na gorze.
- `Sensors` - wszystkie 16 czujnikow jako slupki z wartoscia ADC nad kazdym, od lewej do prawej. Przerywana linia to `Threshold` z zakladki `Drive`, jasne slupki sa powyzej progu, trojkat pod slupkami pokazuje pozycje linii, a kreska obok niego srodek listwy. Pod wykresem `Error` (tak jak w PID: `8500 - pozycja`) i `Position / centre`, np. `10500 / 8500`. Stream dziala tylko gdy robot stoi.
- `Joystick` - reczna jazda, robot staje po puszczeniu albo po 350 ms bez pakietow.
- `Log` - wszystko co poszlo do robota i co wrocilo.
- `Settings` - zmiana nazwy modulu (`BtNameNow`, modul w trybie AT).

## Budowanie

```bash
./gradlew assembleDebug
```

Gradle potrzebuje JDK 17+, np. tego z Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```
