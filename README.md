# Spark Ink

Android-app voor de Wacom Bamboo Spark / Spark Folio (en Slate): haalt je handgeschreven pagina's
via Bluetooth van het pad, zet ze om naar tekst (ML Kit op het toestel, of Claude) en kan ze naar Notion sturen.

- `core/` - het Bluetooth-protocol en de decoder voor de pennendata (pure Kotlin, met tests).
  Gebaseerd op de beschrijving van het open-source project [Tuhi](https://github.com/tuhiproject/tuhi).
- `app/` - de Android-app.

## Installeren

CI bouwt bij elke push een debug-APK en zet die op de release **sparkink-latest**
(download in een browser waarin je bent ingelogd bij GitHub als de repo privé is).
Daarna kun je updaten met de knop **Update** in de app (GitHub-token met alleen leesrechten nodig, zie Instellingen).

## Koppelen

Houd de knop van het pad 6+ seconden ingedrukt tot het lampje knippert, tik op **Folio koppelen**,
kies het pad en bevestig het Bluetooth-koppelverzoek op je telefoon. Synchroniseren: tik op **Synchroniseer**
en druk daarna kort op de knop van het pad. Bij problemen: knop **Log** in de app.

## Lokaal testen

```
CORE_ONLY=1 ./gradlew :core:test      # protocol en parser, zonder Android SDK
./gradlew :app:assembleDebug          # de app (Android SDK nodig)
```
