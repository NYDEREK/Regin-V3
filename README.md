# Regin-V3

Line follower na STM32G474 z 16 czujnikami analogowymi (multiplekser), mostkami BTN9970 i turbina na ESC.

Kod STM32 znajduje sie w:

`SOFTWARE/ReginV3_LFT_Code/Regin-V3-LFT-Code`

Najwazniejsze pliki:

- `Core/Src/Line_Follower.c` - jazda po linii i PID.
- `Core/Src/SimpleParser.c` - komendy Bluetooth z aplikacji.
- `Core/Inc/robot_config.h` - stale robota (PWM, ESC, bateria, Bluetooth).

## Komunikacja z aplikacja GRUZIK4.0

Firmware mowi tym samym protokolem co [GRUZIK4.0](https://github.com/NYDEREK/GRUZIK4.0), wiec Regina mozna sterowac aplikacja Android z tamtego repo (`Android App/RobotApp`). Wystarczy sparowac modul HC-04 (UART 9600) i polaczyc sie z aplikacji.

Co dziala:

- `Drive` - nastawy PID i predkosci, start (`StartNormal=1`, `Mode=Y`) i stop (`Mode=N`).
- `Joystick` - `Manual=<lewy>,<prawy>`, robot staje po 350 ms bez pakietow.
- czyszczenie opon - `CleanSpeed=<pwm>`, `Clean=1`, `Clean=0`.
- `Debug` - `Telemetry=debug` wysyla linie `DBG,...` z pozycja linii i surowymi odczytami czujnikow (od lewej do prawej). Aplikacja pokazuje pierwsze 12 z 16 czujnikow.
- zmiana nazwy modulu - `BtNameNow=<nazwa>` (modul musi byc w trybie AT).

Regin nie ma enkoderow, IMU ani karty SD, wiec zakladki `Mapping` i `Odometry` nie dzialaja. Robot odpowiada wtedy bledem i nie rusza:

```text
MAP_ERROR,unsupported,no_odometry_sd
UPLOAD_ERROR,unsupported
TELEMETRY,odom_unsupported
BT_NAME_ERROR,no_sd_use_try_now
```

Uwagi:

- `Turbine_Speed` z aplikacji to 0..1000, czyli 1000..2000 us na ESC.
- `Turbine_Prep_Time` to czas w ms od wlaczenia turbiny do startu PID. Bez aplikacji domyslnie 1000 ms.
- Ponizej 11 V robot nie wystartuje (`! Low Battery !`).
- Telemetria `DBG` dziala tylko gdy robot stoi, bo wysylanie po UART blokuje odczyt czujnikow.
