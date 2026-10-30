# Färdplan

Tandem byggs i faser. Varje fas slutar med en signerad release som installeras via Obtainium.
Ordningen kan ändras; [CHANGELOG.md](../CHANGELOG.md) visar vad som faktiskt har släppts.

## Fas 0: Grund ✅
Gradle-skelett, CI, signerade releaser, kontraktstester mot en tillfällig Tandoor,
inloggning med en egen token per enhet (lösenordet sparas aldrig), flikar, inställningar,
språk (svenska och engelska), ingången till demoläget, lokal kraschlogg.

## Fas 1: Laga
Levereras i två steg: **0.1.0** (Fas 1a, läsa recept) och **0.2.0** (Fas 1b, laga).

- ✅ Receptlista med sökning offline och mot servern, filter (nyckelord, livsmedel, tid, betyg,
  nedladdade) och sortering (0.1.0).
- ✅ Receptvy: portioner, skalade mängder som i Tandoors webb, bråk, enhetsomräkning, steg,
  betyg (0.1.0).
- ✅ Recept sparade för offline, med bilder (0.1.0).
- ✅ Kokläge: ett steg per sida, mängderna för steget, skärmen hålls på, stor text (0.2.0).
- ✅ Timers som hittas i stegtexten, flera samtidigt, larm även med låst skärm (0.2.0).
- ✅ "Klart!" med betyg och kommentar (Tandoors koklogg) (0.2.0).
- ✅ Fliken Idag: kvällens måltid, ofta lagade, nya recept (0.1.0).
- ✅ Versionskontroll av servern och säkert läge för otestade Tandoor-versioner (0.1.0).
- ✅ Återställningsskärm i stället för kraschloop (0.1.0).
- ✅ Demoläge med inbyggda exempelrecept, för den som inte har någon Tandoor (0.1.0).
- Surfplatta: ✅ lista och recept sida vid sida, ingredienser och steg som rullar var för sig,
  nyckelords- och livsmedelsfiltret som sidopaneler (0.1.0); ✅ kokläge i två paneler (0.2.0).
- ✅ Fler inställningar och en nattlig kontroll mot nya Tandoor-versioner (0.2.0).
- ✅ Tandoor 2.7.1: lösenordsinloggningen via allauth (2.7 tog bort den gamla) och testad (0.2.0).
- ✅ Automatiska tillgänglighetskontroller (ATF) av skärmarna i UI-testerna (0.1.0);
  skärmbildstester mot referensbilder och med pseudospråk för lång text och höger-till-vänster
  (0.2.0).

## Fas 2: Handla och planera ✅
Levererades i **0.3.0**.

- ✅ Inköpslista som fungerar offline och synkas mellan hushållets telefoner (0.3.0).
- ✅ Butikssortering, flera listor, lägga till varor i fritext, butiksläge, köp senare (0.3.0).
- ✅ Recept och kvällens måltid till inköpslistan, med det du har hemma bortvalt (0.3.0).
- ✅ Matsedel: vecka, måltidstyper, skapa och flytta måltider (0.3.0).
- ✅ "Handla för veckan" utan dubbla rader (0.3.0).
- ✅ Böcker, Favoriter och "Vill laga" (0.3.0).
- ✅ Skrivskyddat läge för gäster (0.3.0).
- ✅ Byta mellan konton och servrar i appen (ett aktivt konto per enhet) (0.3.0).
- ✅ Hushållets namn och din roll på kontokortet under Mer (0.3.0).
- ✅ Surfplatta: inköpslistan i två kolumner och veckorutnätet, i båda orienteringarna (0.3.0).

## Fas S: Skafferi och drycker ✅
Levererades i **0.4.0**, före Fas 3 (beslutat 2026-10-29).

- ✅ Skafferi per plats (Kyl, Frys, Skafferi och egna) med bäst före-datum, "Slut" och "−1",
  offline och synkat med Tandoor (0.4.0).
- ✅ Kan laga nu: Har allt, Saknar 1 och Saknar 2 som Tandoor räknar, Överraska mig, och "Lägg det
  som saknas på listan" (0.4.0).
- ✅ Använd upp: det som går ut snart lyfter sina recept, också på Idag (0.4.0).
- ✅ Basvaror och Packa upp efter handlingen (0.4.0).
- ✅ Streckkodsskanning (Open Food Facts, bara koden skickas) och kvitton som läses i telefonen,
  med en granskningslista; inlärda koder och kvittonamn delas av hushållets telefoner (0.4.0).
- ✅ Foto av kylen, experimentellt, med en egen nyckel till Claude, Gemini eller OpenAI (0.4.0).
- ✅ Drycker som recept och kopplingen "Passar med" mellan rätter och drycker, med dryckesförslag
  på Idag och i Veckan (0.4.0).
- ✅ Surfplatta: Hemma och Kan laga nu i kolumner i båda orienteringarna (0.4.0).

## Fas 3: Skapa och fånga
- Skapa och redigera recept i appen, lika komplett som i Tandoors webb: ingredienser med
  livsmedel och enheter, steg, bilder, nyckelord och tider.
- Importera recept genom att dela en länk eller text till appen; foto och PDF tolkas av
  Tandoors AI-import.
- Widgets, snabbinställningsruta och genvägar.
- Dra och släpp i matsedeln, näringsvärden.

## Fas 4: Smart hushåll
- Textigenkänning av recept i telefonen (samma som kvittoskanningen i Fas S).
- Inlärd butiksordning, återkommande varor, förslag på veckans matsedel.
- Notiser när någon i hushållet ändrar listan. Appen kontrollerar i bakgrunden, så ingen extra
  tjänst behövs; snabbare push via en valfri liten tjänst som man kan köra själv.
- Valfri notis när något i skafferiet går ut: "Grädde går ut i morgon – 3 recept" (en daglig
  kontroll i bakgrunden, går att slå av).
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
