# BT FrontCam Test

Minimalna aplikacja diagnostyczna dla Androida 12+.

## Cel

Nagranie wideo z **przedniej kamery** ma zostać zapisane tylko wtedy, gdy
`MediaRecorder.getRoutedDevice()` potwierdzi faktyczny mikrofon Bluetooth.

Aplikacja celowo NIE ufa samemu wyborowi urządzenia (`setPreferredDevice()`).
Jeżeli system/OEM wróci do mikrofonu telefonu:

- nagrywanie jest przerywane,
- plik testowy jest kasowany,
- na ekranie pojawia się `TEST NEGATYWNY`.

## Mechanizm BT

1. wyszukuje wejście `TYPE_BLUETOOTH_SCO`, `TYPE_BLE_HEADSET` lub `TYPE_HEARING_AID`,
2. ustawia `MODE_IN_COMMUNICATION`,
3. wywołuje `AudioManager.setCommunicationDevice()` dla BT,
4. ustawia to samo wejście przez `MediaRecorder.setPreferredDevice()`,
5. nagrywa audio jako `VOICE_COMMUNICATION`,
6. po starcie sprawdza `MediaRecorder.getRoutedDevice()`,
7. pilnuje `OnRoutingChangedListener` podczas całego nagrania.

## Test

1. Podłącz słuchawki Bluetooth i upewnij się, że mają włączoną obsługę rozmów.
2. Uruchom aplikację i nadaj CAMERA, MICROPHONE i Nearby devices/Bluetooth.
3. Kliknij **Sprawdź BT**.
4. Powinny pojawić się `BT INPUT` i `BT COMM`.
5. Kliknij **NAGRYWAJ**.
6. Jeżeli pojawi się `TEST OK`, faktyczny routed device jest BT.
7. Zasłoń mikrofony telefonu i mów bezpośrednio do mikrofonu słuchawek jako dodatkowy test odsłuchowy.
8. Poprawne pliki trafiają do `Movies/BTFrontCamTest`.

## Kompilacja

Projekt jest celowo bez zewnętrznych bibliotek AndroidX.

Android Studio:
- otwórz katalog projektu,
- JDK 17,
- Build > Build APK(s).

GitHub Actions:
- workflow `.github/workflows/build.yml` buduje `app-debug.apk`,
- gotowy APK pojawi się jako artifact `BTFrontCamTest-debug`.

## Diagnostyka

Najważniejsze komunikaty:
- `BT INPUT: BRAK` — Android nie wystawia mikrofonu słuchawek jako input.
- `setCommunicationDevice(BT) zwróciło false` — system odrzucił routing komunikacyjny.
- `TEST NEGATYWNY` — MediaRecorder faktycznie używa innego wejścia, zwykle mikrofonu telefonu.
- `TEST OK` — `getRoutedDevice()` potwierdził wejście BT.
