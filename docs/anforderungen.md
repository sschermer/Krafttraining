# Anforderungen – Trainingsapp

## Datenmodell
Übung (mit Kategorie, Geräten, Art und Kennzeichen Gewicht pro Seite), Plan A/B, Trainingseinheit, Satz und Körpergewicht. Historische Trainingseinheiten werden nicht rückwirkend verändert. Jeder Satz ist ein eigener Datensatz.

## Bedienung
A/B manuell auswählbar. Vor dem Training die Frage: Kondition vorher? Bei Ja optional Art und Dauer. Voreinstellung zwei Sätze; über ein kleines + pro Übung beliebig viele zusätzliche Sätze. Letzte Gewichte und Wiederholungen werden vorgeschlagen; RIR bleibt leer, bis es erfasst wird. Kein Training automatisch speichern, sondern explizit abschließen.

## Fortschritt
Eigene Tabellen je A/B-Plan; Übungsnamen links, Termine chronologisch nach rechts. Aktuellster Wert rechts, horizontal scrollbar. Bei gleichem Gewicht: `30 / 10-10`; beim isometrischen Wandsitz: `45s-40s`. Unterschiedliche Satzgewichte werden eindeutig einzeln dargestellt. Zeitreihen zur Trainingsentwicklung.

## Körpergewicht
Beliebig viele Messungen, jeweils mit Datum und Uhrzeit. Automatische Uhrzeit mit manueller Korrekturmöglichkeit. Zeitreihen-Kurve.

## Export
Excel XLSX; CSV (Satzdaten); JSON (vollständiges Backup samt Import). Keine Daten in Quellcode oder öffentliche Repositories übernehmen.

## Offene Erweiterungen
Trainingszeiträume als explizite Plan-Snapshots; differenzierte Auswertungen und Wiederherstellungs-Validierung.

## Verbindliche Schutzregeln ab Version 0.2

- Kein Übernehmen vorausgefüllter Werte in die Historie ohne explizites Bestätigen des betreffenden Satzes.
- Jeder Satz besitzt im laufenden Training einen Erledigt-Status. Nicht bestätigte Sätze werden beim Abschluss ignoriert.
- Ein begonnenes Training und Änderungen an dessen Sätzen müssen einen Neustart der App überleben.
- Eine während des Trainings geänderte Auswahl auf der Startseite darf nicht den A/B-Typ des aktiven Trainings verändern.
- Datenbankänderungen dürfen ältere Trainingseinheiten nicht löschen.

## Version 0.3.0 – verbindliche neue Regeln

- Trainingseinheiten dürfen rückwirkend mit tatsächlichem Datum und Uhrzeit erfasst werden. Nachträgliche Korrektur des gespeicherten Trainings einschließlich Satzwerte ist möglich. Tabellen sortieren nach dem eingetragenen Trainingszeitpunkt.
- Trainingsphasen verwalten eigene A/B-Pläne. Abschluss macht die Phase unveränderlich, ohne Trainingsdaten zu löschen. Beim Anlegen einer neuen Phase sind leerer Plan und Kopie der letzten Phase verfügbar.
- Nur die aktive Phase ist beim Starten neuer Trainings auswählbar; archivierte Phasen sind für Historie und Auswertung weiterhin lesbar.
- Übungsauswahl nach Kategorien, standardmäßig alle geschlossen. Nur eine Kategorie gleichzeitig aufklappbar. Bereits ausgewählte Übungen erhalten pro Kategorie einen Zähler in Klammern; ausgewählte Übungen tragen Häkchen.
- Historische Pläne und Übungen dürfen nicht durch neue Trainingsphasen überschrieben werden.
- Der Datenbankwechsel muss bestehende Daten erhalten, ältere Sicherungen importieren und neue Phasen exportieren.
