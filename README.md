# RadioPult – UNDOK alternative for Android

[![Download APK](https://img.shields.io/github/v/release/CSchoch/RadioPult?label=Download%20APK&logo=android)](https://github.com/CSchoch/RadioPult/releases/latest)

**RadioPult is a free Android remote control for Wi-Fi / internet radios with a Frontier Silicon module, the radios that are normally controlled with the UNDOK app.** If UNDOK no longer works for you or is no longer available, RadioPult controls the same radios over your home Wi-Fi.

*Deutsch: RadioPult ist eine kostenlose UNDOK-Alternative für Android. Die deutsche Beschreibung steht [weiter unten](#deutsch).*

**Download:** the latest APK is on the [Releases page](https://github.com/CSchoch/RadioPult/releases/latest). Download `RadioPult-….apk` on your phone, open it and allow installing from this source. New versions install as updates over the old one.

## Which radios work?

Any radio that works with **UNDOK** should work with RadioPult. These radios use a Frontier Silicon module (for example Venice 6.5 / Venice X) and its network remote interface (FSAPI / "NetRemote"). They are sold by many brands, for example **Hama, Medion, Roberts, Technisat, Sangean, Silvercrest, Blaupunkt, Auna** and others. If your radio has a menu item like *Network › NetRemote PIN Setup*, it is very likely supported.

Did RadioPult work (or not work) with your radio? Please [open an issue](https://github.com/CSchoch/RadioPult/issues) with the model name, so others can find it.

## Features

- First-time setup: bring a new radio from its setup Wi-Fi into your home Wi-Fi and name it (like "Set up audio system" in UNDOK)
- Finds radios on the network automatically (or enter the IP address)
- Power on / standby, source (internet radio, DAB, FM, Bluetooth, AUX …), volume and mute
- Play / stop, next / previous, station name, track info and station logo
- Presets (favorites): recall, save the current station or any station from the list, delete, reorder
- Station list for internet radio and DAB (with search and DAB scan), CD track list, USB / music browsing
- Equalizer presets, custom bass / treble and loudness
- Settings: device info, rename radio, language, sleep timer, date and time, factory reset
- Remembers the last radio and its PIN and reconnects on start
- No account, no ads, no tracking: the app controls the radio directly in your local network (only station logos are loaded from the address the radio reports)

Not included (compared to UNDOK): setup via WPS or LAN cable, multiroom, alarms, Spotify and other streaming services. RadioPult is Android only (Android 8.0 or newer); there is no iPhone version.

## Connecting

- Phone and radio must be on the **same Wi-Fi**.
- The **PIN** is `1234` on most radios. You can find it on the radio under *Settings › Network › NetRemote PIN Setup*.
- If the radio is not found automatically, look up its IP address on the radio (*Network › View settings*) or in your router and enter it manually.

## License

RadioPult is free software under the [GNU General Public License v3.0](LICENSE).

---

<a id="deutsch"></a>

## Deutsch

Eigene Android-Fernbedienung für WLAN-Radios mit Frontier-Silicon-Modul (die Geräte, die sonst mit UNDOK gesteuert werden, z. B. viele Modelle von Hama, Medion, Roberts, Technisat, Sangean, Silvercrest u. a.). Kostenlose UNDOK-Alternative, wenn die UNDOK-App nicht mehr funktioniert oder nicht mehr verfügbar ist.

**Download:** Das aktuelle APK gibt es auf der [Releases-Seite](https://github.com/CSchoch/RadioPult/releases/latest). Am Handy `RadioPult-….apk` herunterladen, öffnen und die Installation aus dieser Quelle erlauben.

### Was die App kann

- Ersteinrichtung: neues Radio über sein Setup-WLAN ins Heim-WLAN bringen und benennen (wie „Audiosystem einrichten“ in UNDOK)
- Radios im WLAN automatisch finden (oder IP-Adresse manuell eingeben)
- Ein/Aus (Standby)
- Quelle wählen (Internetradio, DAB, UKW, Bluetooth, AUX …, je nach Gerät)
- Lautstärke und Stummschaltung
- Abspielen/Stopp, Vor/Zurück
- Anzeige von Sender, Titel/Info-Text und Senderlogo
- Favoriten (Presets) der aktuellen Quelle anzeigen und abrufen
- Favoriten bearbeiten: aktuellen Sender oder direkt einen Sender aus der Senderliste (☆) auf einem Platz speichern, löschen, Reihenfolge ändern
- Senderliste für Internetradio und DAB (inkl. Suche und DAB-Sendersuchlauf), Titelliste für CD, Durchsuchen von USB/Musik
- Einstellungen: Geräteinfos (Modell, Firmware, IP, MAC, WLAN und Signalstärke), Radio umbenennen, Sprache, Sleep-Timer, Datum & Uhrzeit (12/24 Stunden, Zeitquelle), Werkseinstellungen
- Equalizer: Klang-Preset wählen (Normal, Jazz, Rock …), im eigenen Preset („My EQ“) Bass/Höhen und Loudness einstellen
- Merkt sich das zuletzt genutzte Radio und die PIN: beim Start verbindet die App sich direkt mit dem letzten Radio, bekannte Radios verbinden per Antippen ohne PIN-Abfrage (PIN ändern: Radio lange gedrückt halten)

Nicht enthalten (im Vergleich zu UNDOK): Einrichtung per WPS oder LAN-Kabel, Multiroom, Wecker, Spotify/Streamingdienste.

### So baust du die App

1. **Android Studio** installieren: https://developer.android.com/studio
2. Den Ordner `RadioPult` entpacken und in Android Studio mit **File › Open** öffnen.
3. Warten, bis der „Gradle Sync“ fertig ist (lädt beim ersten Mal einiges herunter). Falls Android Studio ein Upgrade des Android-Gradle-Plugins vorschlägt, kannst du das ablehnen.
4. Entweder:
   - **Direkt aufs Handy:** Am Handy die Entwickleroptionen und USB-Debugging aktivieren, per USB verbinden, oben das Gerät wählen und auf ▶ **Run** tippen.
   - **Als APK-Datei:** **Build › Build App Bundle(s) / APK(s) › Build APK(s)**. Die Datei liegt danach unter `app/build/outputs/apk/debug/app-debug.apk` (installiert sich als „RadioPult Test“ neben der Release-App). Aufs Handy kopieren und öffnen (Installation aus unbekannten Quellen erlauben).

### Verbinden

- Handy und Radio müssen im **selben WLAN** sein.
- Die **PIN** ist bei den meisten Geräten `1234`. Am Radio steht sie meist unter *Einstellungen › Netzwerk › NetRemote PIN-Einstellungen*.
- **Neues Radio:** In der Geräteliste auf „Neues Radio einrichten“ tippen. Das Radio muss im Setup-Modus sein (neu bzw. nach dem Zurücksetzen, oder am Radio *Netzwerk › Netzwerkassistent*). Ab Android 10 verbindet sich die App selbst mit dem Setup-WLAN, sonst einmal in den WLAN-Einstellungen mit „Setup …“ verbinden.
- Wird das Radio nicht automatisch gefunden: IP-Adresse am Radio nachsehen (*Netzwerk › Einstellungen anzeigen*) oder im Router und manuell eingeben.

### Technik

- Kotlin, Jetpack Compose, Material 3, minSdk 26 (Android 8.0)
- Gerätesuche per SSDP, Steuerung über die HTTP-Schnittstelle der Radios (`/fsapi/GET|SET|LIST_GET_NEXT/...`) – keine zusätzlichen Bibliotheken
- Code: `app/src/main/java/de/radiopult/app/`
  - `fsapi/FsApiClient.kt` – Schnittstelle zum Radio
  - `fsapi/Discovery.kt` – Gerätesuche
  - `fsapi/RadioSetup.kt` – Ersteinrichtung (WLAN-Suche, Passwort-Verschlüsselung)
  - `setup/` – Setup-WLAN und Ablauf der Einrichtung
  - `RadioViewModel.kt` – Zustand und Befehle
  - `fsapi/Values.kt` – Rohwerte des Radios lesbar machen (IP-Adresse, Sprachen, Zeitquellen)
  - `ui/` – Bildschirme (`EqualizerSheet.kt` – Equalizer, `SettingsScreens.kt` – Einstellungen, `FavoritesScreen.kt` – Favoriten bearbeiten, `BrowseScreen.kt` – Sender-/Titelliste)

### Releases (APK automatisch bauen)

Der Workflow `.github/workflows/release.yml` baut bei jedem Tag `v*` ein Release-APK und hängt es an ein GitHub-Release:

```
git tag v1.1
git push origin v1.1
```

Alternativ unter **Actions › Release APK › Run workflow** einen Tag eingeben.

Damit spätere Versionen sich über eine installierte App aktualisieren lassen, braucht es einen festen Signaturschlüssel. Einmalig erzeugen und als Repository-Secrets (**Settings › Secrets and variables › Actions**) hinterlegen:

```
keytool -genkeypair -v -keystore radiopult.jks -alias radiopult -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 radiopult.jks   # Ausgabe -> RADIOPULT_KEYSTORE_BASE64
```

| Secret | Inhalt |
|---|---|
| `RADIOPULT_KEYSTORE_BASE64` | Keystore-Datei, base64-kodiert |
| `RADIOPULT_KEYSTORE_PASSWORD` | Keystore-Passwort |
| `RADIOPULT_KEY_ALIAS` | Alias, z. B. `radiopult` |
| `RADIOPULT_KEY_PASSWORD` | Schlüssel-Passwort |

Ohne diese Secrets bricht der Workflow ab, statt ein APK mit wechselndem Schlüssel zu veröffentlichen, das sich nicht über die installierte App aktualisieren lässt. Die Keystore-Datei und die Passwörter gut aufbewahren: Mit einem neuen Schlüssel muss die App einmal deinstalliert werden (dabei gehen die gespeicherten PINs verloren).

Test-Builds (`assembleDebug`) werden mit dem eingecheckten `app/debug.keystore` signiert und als eigene App „RadioPult Test“ (`de.radiopult.app.debug`) installiert. Sie lassen sich untereinander aktualisieren, egal wo sie gebaut wurden, und kommen der Release-App nicht in die Quere.

### Lizenz

RadioPult ist freie Software unter der [GNU General Public License v3.0](LICENSE).
