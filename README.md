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
