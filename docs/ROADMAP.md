# Färdplan

Tandem byggs i faser. Varje fas slutar med en signerad release som installeras via Obtainium.
Ordningen kan ändras; [CHANGELOG.md](../CHANGELOG.md) visar vad som faktiskt har släppts.

## Fas 0: Grund ✅
Gradle-skelett, CI, signerade releaser, kontraktstester mot en tillfällig Tandoor,
inloggning med en egen token per enhet (lösenordet sparas aldrig), flikar, inställningar,
språk (svenska och engelska), ingången till demoläget, lokal kraschlogg.

## Fas 1: Laga
- Receptlista med sökning offline och mot servern, filter och sortering.
- Receptvy: portioner, skalade mängder, bråk, enhetsomräkning, steg, betyg.
- Recept sparade för offline, med bilder.
- Kokläge: ett steg per sida, mängderna för steget, skärmen hålls på, stor text.
- Timers som hittas i stegtexten, flera samtidigt, larm även med låst skärm.
- "Klart!" med betyg och kommentar (Tandoors koklogg).
- Fliken Idag: kvällens måltid, ofta lagade, nya recept.
- Versionskontroll av servern och säkert läge för otestade Tandoor-versioner.
- Återställningsskärm i stället för kraschloop.
- Demoläge med inbyggda exempelrecept, för den som inte har någon Tandoor.
- Surfplatta: lista och recept sida vid sida, kokläge i två paneler.

## Fas 2: Handla och planera
- Inköpslista som fungerar offline och synkas mellan hushållets telefoner.
- Butikssortering, flera listor, lägga till varor i fritext, butiksläge, köp senare.
- Matsedel: vecka, måltidstyper, skapa och flytta måltider.
- "Handla för veckan" utan dubbla rader.
- Böcker, Favoriter och "Vill laga".
- Skrivskyddat läge för gäster.
- Byta mellan konton och servrar i appen (ett aktivt konto per enhet).

## Fas 3: Skapa och fånga
- Skapa och redigera recept i appen, lika komplett som i Tandoors webb: ingredienser med
  livsmedel och enheter, steg, bilder, nyckelord och tider.
- Importera recept genom att dela en länk eller text till appen; foto och PDF tolkas av
  Tandoors AI-import.
- Widgets, snabbinställningsruta och genvägar.
- Dra och släpp i matsedeln, "Kan laga nu", "Överraska mig", näringsvärden.

## Fas 4: Smart hushåll
- Skafferi med utgångsdatum, streckkodsskanning (Open Food Facts) och textigenkänning.
- "Använd upp", inlärd butiksordning, återkommande varor, förslag på veckans matsedel.
- Notiser när någon i hushållet ändrar listan. Appen kontrollerar i bakgrunden, så ingen extra
  tjänst behövs; snabbare push via en valfri liten tjänst som man kan köra själv.
- Två recept samtidigt i kokläget.

## Fas 5: Plattform
- Handsfree-kokläge med röst och uppläsning.
- Wear OS-app (till exempel Samsung Galaxy Watch 4 och senare): inköpslista och timers.
- Påminnelse nära butiken, matsedeln i kalendern (iCal), köksskärmsläge för surfplattan.

## Övrigt
- **Distribution:** GitHub Releases + Obtainium nu, Google Play senare. F-Droid är inte planerat.
- **iOS:** Android går först. Logiken hålls i ren Kotlin så att en iOS-version
  (Kotlin Multiplatform) blir möjlig senare, men den är inte planerad.
- **Nya Tandoor-versioner:** CI testar varje ny Tandoor-version nattligt, och appen visar en
  varning och går i säkert läge om servern kör en version som inte är testad.
