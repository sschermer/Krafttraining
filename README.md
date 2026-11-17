# Trainingsapp

Native Android-App (Kotlin) zur lokalen Erfassung von Krafttraining.

## Version 0.1 – erster Implementierungsstand

- 43 vorinstallierte Übungen in 8 Kategorien (inklusive Wandsitz, Plank und Side Plank)
- Eigene Übungen und Kategorien hinzufügen bzw. bearbeiten
- Trainingspläne A und B mit frei wählbaren Übungen
- Vor Trainingsbeginn Abfrage, ob Konditionstraining durchgeführt wurde (Art und Dauer)
- Zwei voreingestellte Sätze je Übung, weitere über **+ Satz**
- Gewichtseingabe pro Seite, Wiederholungen, Sekunden bei isometrischen Übungen und optional RIR
- Zuletzt erfasste Werte werden beim nächsten Training als Eingabevorschlag verwendet
- SQLite-Datenbank speichert Trainingseinheiten dauerhaft
- Fortschrittstabelle: chronologisch links nach rechts; neueste Ergebnisse ganz rechts; `30 / 10-10`, `45s-40s`
- Einfaches Liniendiagramm pro Übung
- Körpergewicht jederzeit mit Datum/Uhrzeit erfassen und als Verlauf ansehen
- Export als XLSX, CSV und vollständiges JSON-Backup, Import des JSON-Backups

## Android-Build

Voraussetzungen: Android SDK (API 35), JDK 17, Gradle 8.7.

```sh
gradle :app:assembleDebug
```

GitHub Actions baut bei jedem Push auf `main` eine Debug-APK und stellt sie als herunterladbares Workflow-Artefakt bereit: **Actions → Android APK → letzter erfolgreicher Lauf → Artifacts**. Eine Debug-APK kann für Tests installiert werden, ist aber noch kein dauerhaft signiertes Release. Release-Signierung darf niemals im öffentlichen Repository abgelegt werden.

## Daten und Einschränkungen

Daten bleiben lokal auf dem Gerät. Eine vollständige JSON-Sicherung dient zum Wiederherstellen; beim Import werden aktuelle lokale Daten ersetzt. XLSX ist für die Auswertung, nicht zum Wiederherstellen. Fortschrittskurven zeigen derzeit den jeweils höchsten eingetragenen Gewichts-/Zeitwert pro Training. Vorinstallierte A/B-Pläne sind Beispiele und frei anpassbar. Trainingshistorie bleibt erhalten, wenn Übungen aus Plänen entfernt werden.

**Hinweis zum Gewicht:** Bei Übungen mit `Gewicht pro Seite` ist die Eingabe für jeweils eine Seite vorgesehen, nicht das Gesamtgewicht. Das Kennzeichen bleibt im Export erhalten.

## Weiterentwicklung

- Persistente Trainingszeiträume mit archivierten Planversionen
- Verbesserte Statistiken (Trainingsvolumen, Bestleistungen, Vergleich von Wiederholungen und RIR)
- Ausführlichere Konfigurations- und Filtermöglichkeiten
- Signierte Release-APK nach erfolgreichem Test

Keine personenbezogenen Trainingsdaten in das GitHub-Repository hochladen.


# Android-Signierung und Updates

## Einmalige Einrichtung

Die Trainingsapp verwendet ab Version 0.1.1 einen **festen privaten Release-Schlüssel**.
Die Datei `Trainingsapp-Signierschluessel.p12` wird **niemals** in diesem öffentlichen Repository gespeichert.

Der Besitzer erhält privat zwei Dateien:
1. `Trainingsapp-Signierschluessel.p12` — dauerhafter privater Signierschlüssel; offline sichern.
2. `Trainingsapp-GitHub-Secrets.txt` — enthält die beiden für GitHub Actions benötigten Geheimwerte; getrennt und geschützt sichern.

In GitHub (am Laptop im Browser):
1. `Settings → Secrets and variables → Actions → New repository secret` öffnen.
2. `ANDROID_KEYSTORE_BASE64` mit dem gleichnamigen Wert aus der privaten Textdatei erstellen.
3. `ANDROID_SIGNING_PASSWORD` mit dem gleichnamigen Wert erstellen.
4. Unter `Actions → Android APK → Run workflow → Run workflow` einen neuen Build starten.
5. Nach erfolgreicher Signaturprüfung erscheint eine aktualisierbare APK unter `Releases`.

Die Signierkonfiguration liest den Keystore ausschließlich über GitHub Actions Secrets.
Bei fehlenden Secrets baut der Workflow nur eine Debug-APK und veröffentlicht **keine**
als signiert bezeichnete Release-APK.

Der SHA-256-Zertifikatsfingerabdruck ist im Workflow hinterlegt. Signaturen, die
nicht diesem privaten Schlüssel entsprechen, werden nicht veröffentlicht.

## Wichtig beim Umstieg

Die bestehende Version 0.1.0 ist mit einem temporären **Debug-Schlüssel** signiert.
Android akzeptiert die neue Release-Signatur daher **nicht als direktes Update**
über der alten Debug-APK.

Falls bereits Trainingsdaten vorhanden sind:
1. In der alten App **Daten → Sicherung exportieren (.json)** ausführen.
2. Die JSON-Sicherung außerhalb der App speichern.
3. Die alte Debug-App deinstallieren.
4. Die neue **signierte** APK installieren.
5. In der neuen App die JSON-Sicherung importieren.

Danach können zukünftige **signierte** APKs mit derselben `applicationId`,
demselben Schlüssel und höherer `versionCode` ohne Deinstallation installiert
werden. Der Build verwendet dazu die steigende GitHub-Actions-Laufnummer.

Der private Schlüssel und die zugehörigen Passwörter müssen dauerhaft sicher
aufbewahrt werden. Gehen sie verloren, ist eine reibungslose Update-Kette
nicht mehr möglich.

## Technischer Status

- Test-Builds (Debug): unabhängig von den Secrets
- Signierter Build: nur auf `main` nach Einrichtung der Secrets
- `apksigner verify` und Prüfung des Zertifikats vor der Veröffentlichung
- Neue signierte Testversionen unter GitHub Releases, nicht nur Actions-Artefakten
