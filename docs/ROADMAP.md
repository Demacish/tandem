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

## Fas 3: Skapa och fånga ✅
Levererades i **0.5.0** (2026-11-03).

- ✅ Skapa och redigera recept i appen: namn, beskrivning, foto, portioner, tider, nyckelord,
  steg med ingredienser (livsmedel och enheter på namn), offline med tillfälligt id tills det
  skickats, konflikt när receptet ändrats på webben under tiden, ta bort recept (0.5.0). Ta bort
  bilden, konflikt också när bara ett steg ändrats på webben, stegets rubrik och ingredienslista,
  privat recept, delrecept, stegfiler och receptets egna näringsvärden (0.6.0). Kvar: en PDF på ett
  steg öppnas på webben (appen visar bara bilder).
- ✅ Importera recept genom att dela en länk eller text till appen (en text tolkas i appen, en
  länk av er Tandoor), med granskning innan receptet öppnas i redigeraren; foto och PDF tolkas
  av Tandoors AI-import när utrymmet har en AI-leverantör (0.5.0). En sida som stänger ute
  servern läses i appen och skickas till Tandoor utan AI, ger den inget kan den delade texten
  tolkas av AI:n på begäran, och AI:n kan fördela ingredienserna på stegen (0.6.0). Flera fotade
  sidor blir ett recept, och granskningen visar originalet bredvid tolkningen (0.6.0). Avsteg från
  planens tvåspaltsimport på surfplatta: en länk eller en text har inget original att visa, så de
  är en kolumn på alla bredder (se BYGGE). En notis säger till när en AI-import blir klar medan
  appen inte är på skärmen, och svaret finns kvar även om systemet stänger appen under tiden. Kvar:
  AI-importen och stegfördelningen är bara prövade mot en server utan leverantör.
- ✅ Widgets (inköpslistan med avbockning, dagens måltider), snabbinställningsrutan "Handla" och
  genvägar på appikonen (0.5.0).
- ✅ Dra och släpp i matsedeln (dag, och måltidstyp på surfplattan) och näringsvärden per portion i
  receptvyn (0.5.0), i kJ eller kcal som i Tandoor (0.6.0). Recept dras in från "Vill laga" och, på
  bred surfplatta, från en receptsökning bredvid veckan (0.6.0).
- ✅ Hemsektioner som du ordnar och döljer ("Anpassa Idag", 0.6.0).

## Fas 4: Smart hushåll ✅
Levererades i **0.6.0** (2026-11-05), med det som återstod av Fas 3.

- ✅ Textigenkänning av recept i telefonen (samma ML Kit-läsare som kvittoskanningen i Fas S):
  "Fota recept" i Importera recept läser en kokbokssida, ett kort eller en utskrift utan AI-tjänst,
  även sidor i två spalter. Texten hamnar i Importera recept och blir ett utkast som öppnas i
  redigeraren. Kvar: handskrivet.
- ✅ Inlärd butiksordning, återkommande varor, förslag på veckans matsedel: butiksläget erbjuder
  att spara ordningen du handlade i som butikens avdelningsordning; "Dags igen?" i Lägg till föreslår
  varor som brukar köpas med jämna mellanrum; "Föreslå veckans matsedel" i Veckan fyller lediga
  dagar med recept ni lagar ofta men inte nyligen, med valda nyckelord och måltid, utan dubbletter
  och med det som snart går ut hemma först. Butiksordningen föreslås efter tre turer som stämmer
  till minst 80 %. Allt räknas lokalt (Tandoors auto-plan används inte, se BYGGE).
- ✅ Notiser när någon i hushållet ändrar listan: bakgrundssynken ser andras ändringar och en
  notis (av som standard, slås på under Synk och lagring) visar dem när appen inte är på skärmen.
  Snabbare push via en valfri liten tjänst som man kör själv (`push-service/`, docs/PUSH.md):
  tjänsten kontrollerar listan var 30–60:e sekund och väcker telefonerna via UnifiedPush (t.ex.
  ntfy), så en ändring syns inom en minut även när appen är stängd. Experimentell, av som standard,
  och kräver en egen tjänst; tjänstens image publiceras med versionstaggen.
- ✅ Valfri notis när något i skafferiet går ut: "Grädde går ut i morgon – 3 recept" (en daglig
  kontroll i bakgrunden på morgonen, fungerar utan nät, av som standard; erbjuds på Hemma och
  under Synk och lagring). Tryck öppnar Kan laga nu med det som ska användas upp först. Kvar: en
  egen tid för påminnelsen.
- ✅ Två recept samtidigt i kokläget: "Laga ett recept till" öppnar ett andra recept som flik,
  varje recept har sitt eget steg och "Klart!", och timrarna från båda syns (med receptets namn,
  även i notiserna) och ringer. "Laga" medan ett annat recepts timer går öppnar båda, också från
  Kan laga nu. Kvar: två recept sida vid sida på riktigt breda skärmar.
- ✅ Dela recept: receptets meny delar en Tandoor-länk, som öppnas utan inloggning, eller receptet som
  text (0.6.0). Länken finns inte i demoläget, för gäster eller när utrymmet har delning avstängd.

## Fas 5: Plattform
Fasen är löpande och släpps i flera versioner. Första delen, **0.7.0 "I köket"** (2026-11-13), och
andra delen, **0.8.0 "På språng"** (2026-11-14), levererade punkterna markerade ✅. Resten kommer i
senare versioner: en inkorg för recept från RSS, App Functions, biometriskt lås och target SDK 37.

- ✅ Handsfree-kokläge (0.7.0): volymknapparna byter steg, "nästa", "tillbaka" och "upprepa" med
  taligenkänning i telefonen (aldrig via nätet) och uppläsning av stegen. Av som standard; slås på i
  kokläget eller i Inställningar → Kokläge och timers. Kvar: köksprovet med fläkten på (9 av 10)
  görs på en riktig telefon.
- ✅ Köksskärmsläge för surfplattan (0.7.0): mörkt, mycket stor text, skärmen på, dagens recept med
  ett tryck till kokläget och timrarna stora; från Idag och Mer. Kvar: tre timmar på en riktig
  surfplatta.
- ✅ Pannstorlek (0.7.0): skala ett recept efter formen, till exempel från 24 till 20 cm (efter ytan,
  som Tandoors webb), i receptet och kokläget. Efter 0.7.0: "Lägg på inköpslistan" lägger på mängderna
  för den valda formen (portioner × formens faktor, som webben).
- ✅ Sammanslagning i inköpslistans vy: samma vara i enheter som går att räkna om på en rad
  ("3 dl + 200 g grädde" blir "5 dl"), med Tandoors omräkningar, också offline; det som inte går att
  räkna om står kvar på samma rad. Att bocka av raden bockar av alla dess rader (0.7.0).
- ✅ Rutnätsvy med stora rutor på inköpslistan, som tillval i listans meny, sparad per konto (0.7.0).
- ✅ Påminnelse kvällen innan, till exempel "Tina ut kycklingen", från en rad "Påminn:" i måltidens
  anteckning eller fältet "Påminn kvällen innan" när måltiden planeras; valfri, med vald tid (0.7.0).
- ✅ Morgonnotisen om det som går ut på en tid du väljer, och rätt klockslag efter sommartid (0.7.0).
- ✅ Wear OS-app (0.8.0; Wear OS 5, till exempel Samsung Galaxy Watch 4 och senare): inköpslistan att
  bocka av också med telefonen avstängd, med klockans egen nyckel, telefonens timrar och en ruta.
  Installeras från en dator (docs/WEAR.md). Kvar: provet på en riktig klocka.
- ✅ Matsedeln i kalendern (0.8.0): som en egen kalender på telefonen eller som en
  prenumerationslänk (iCal) med en egen nyckel som bara läser matsedeln. Kvar: provet i Samsungs och
  Googles kalendrar.
- ✅ Påminnelse nära butiken (0.8.0): en notis per besök när något står kvar på listan, med
  geofences och platser som stannar i telefonen. Kvar: provet i en riktig butik och batteriet under
  en dag.

Kommer senare:

- Inkorg för recept från RSS-flöden.
- App Functions (appen nås från systemets assistent), biometriskt lås, target SDK 37.
- Två recept sida vid sida i kokläget på en riktigt bred skärm (0.7.0 har flikar på alla bredder).

## Övrigt
- **Distribution:** GitHub Releases + Obtainium nu, Google Play senare. F-Droid är inte planerat.
- **iOS:** Android går först. Logiken hålls i ren Kotlin så att en iOS-version
  (Kotlin Multiplatform) blir möjlig senare, men den är inte planerad.
- **Nya Tandoor-versioner:** CI testar varje ny Tandoor-version nattligt, och appen visar en
  varning och går i säkert läge om servern kör en version som inte är testad.
