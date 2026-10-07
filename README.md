# Regin-V3

Line follower na STM32G474 z 16 czujnikami analogowymi (multiplekser), mostkami BTN9970 i turbina na ESC.

Kod STM32 znajduje sie w:

`SOFTWARE/ReginV3_LFT_Code/Regin-V3-LFT-Code`

Najwazniejsze pliki:

- `Core/Src/Line_Follower.c` - jazda po linii i PID.
- `Core/Src/SimpleParser.c` - komendy Bluetooth z aplikacji.
- `Core/Inc/robot_config.h` - stale robota (PWM, ESC, bateria, Bluetooth).

## Aplikacja Android

Wlasna aplikacja Regina jest w `SOFTWARE/Android App/ReginApp`, gotowy plik: `apk/REGIN-V3-debug.apk`. Ma zakladki `Drive` (turbina, PID, predkosci), `Sensors` (wszystkie 16 czujnikow jako slupki z wartosciami), `Joystick`, `Log` i `Settings`. Szczegoly w README aplikacji.

Firmware mowi tym samym protokolem co [GRUZIK4.0](https://github.com/NYDEREK/GRUZIK4.0), wiec dziala tez aplikacja z tamtego repo (`Android App/RobotApp`), ale pokazuje tylko 12 czujnikow i ma zakladki mapowania, ktorych Regin nie obsluguje. Modul Bluetooth to HC-04 na UART 9600.

Komendy:

- nastawy: `Kp`, `Kd`, `Treshold`, `Base_speed`, `Max_speed`, `Sharp_bend_speed_left/right`, `Bend_speed_left/right`, `Turbine_Speed`, `Turbine_Prep_Time`.
- start i stop: `StartNormal=1`, `Mode=Y`, `Mode=N`.
- joystick: `Manual=<lewy>,<prawy>`, robot staje po 350 ms bez pakietow.
- czyszczenie opon: `CleanSpeed=<pwm>`, `Clean=1`, `Clean=0`.
- czujniki: `Telemetry=debug` wysyla co 250 ms linie `DBG,<pozycja>,<aktywne>,<ostatni koniec>,<9 pol enkoderow/IMU = 0>,<S1>..<S16>` (czujniki od lewej do prawej), `Telemetry=off` konczy.
- nazwa modulu: `BtNameNow=<nazwa>` (modul musi byc w trybie AT).

Regin nie ma enkoderow, IMU ani karty SD, wiec mapowanie i odometria (zakladki `Mapping` i `Odometry` w aplikacji GRUZIK4.0) nie dzialaja. Robot odpowiada wtedy bledem i nie rusza:

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
