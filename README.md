# RadioPult

Eigene Android-Fernbedienung für WLAN-Radios mit Frontier-Silicon-Modul (die Geräte, die sonst mit UNDOK gesteuert werden, z. B. viele Modelle von Hama, Medion, Roberts, Technisat, Sangean, Silvercrest u. a.).

## Was die App kann

- Ersteinrichtung: neues Radio über sein Setup-WLAN ins Heim-WLAN bringen und benennen (wie „Audiosystem einrichten“ in UNDOK)
- Radios im WLAN automatisch finden (oder IP-Adresse manuell eingeben)
- Ein/Aus (Standby)
- Quelle wählen (Internetradio, DAB, UKW, Bluetooth, AUX …, je nach Gerät)
- Lautstärke und Stummschaltung
- Play/Pause, Vor/Zurück
- Anzeige von Sender, Titel/Info-Text und Senderlogo
- Favoriten (Presets) der aktuellen Quelle anzeigen und abrufen
- Equalizer: Klang-Preset wählen (Normal, Jazz, Rock …), im eigenen Preset („My EQ“) Bass/Höhen und Loudness einstellen
- Merkt sich das zuletzt genutzte Radio und die PIN

Nicht enthalten (im Vergleich zu UNDOK): Einrichtung per WPS oder LAN-Kabel, Multiroom, Wecker/Sleep-Timer, Durchsuchen der Internetradio-Senderliste, Spotify/Streamingdienste.

## So baust du die App

1. **Android Studio** installieren: https://developer.android.com/studio
2. Den Ordner `RadioPult` entpacken und in Android Studio mit **File › Open** öffnen.
3. Warten, bis der „Gradle Sync“ fertig ist (lädt beim ersten Mal einiges herunter). Falls Android Studio ein Upgrade des Android-Gradle-Plugins vorschlägt, kannst du das ablehnen.
4. Entweder:
   - **Direkt aufs Handy:** Am Handy die Entwickleroptionen und USB-Debugging aktivieren, per USB verbinden, oben das Gerät wählen und auf ▶ **Run** tippen.
   - **Als APK-Datei:** **Build › Build App Bundle(s) / APK(s) › Build APK(s)**. Die Datei liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`. Aufs Handy kopieren und öffnen (Installation aus unbekannten Quellen erlauben).

## Verbinden

- Handy und Radio müssen im **selben WLAN** sein.
- Die **PIN** ist bei den meisten Geräten `1234`. Am Radio steht sie meist unter *Einstellungen › Netzwerk › NetRemote PIN-Einstellungen*.
- **Neues Radio:** In der Geräteliste auf „Neues Radio einrichten“ tippen. Das Radio muss im Setup-Modus sein (neu bzw. nach dem Zurücksetzen, oder am Radio *Netzwerk › Netzwerkassistent*). Ab Android 10 verbindet sich die App selbst mit dem Setup-WLAN, sonst einmal in den WLAN-Einstellungen mit „Setup …“ verbinden.
- Wird das Radio nicht automatisch gefunden: IP-Adresse am Radio nachsehen (*Netzwerk › Einstellungen anzeigen*) oder im Router und manuell eingeben.

## Technik

- Kotlin, Jetpack Compose, Material 3, minSdk 26 (Android 8.0)
- Gerätesuche per SSDP, Steuerung über die HTTP-Schnittstelle der Radios (`/fsapi/GET|SET|LIST_GET_NEXT/...`) – keine zusätzlichen Bibliotheken
- Code: `app/src/main/java/de/radiopult/app/`
  - `fsapi/FsApiClient.kt` – Schnittstelle zum Radio
  - `fsapi/Discovery.kt` – Gerätesuche
  - `fsapi/RadioSetup.kt` – Ersteinrichtung (WLAN-Suche, Passwort-Verschlüsselung)
  - `setup/` – Setup-WLAN und Ablauf der Einrichtung
  - `RadioViewModel.kt` – Zustand und Befehle
  - `ui/` – Bildschirme (`ui/EqualizerSheet.kt` – Equalizer)
