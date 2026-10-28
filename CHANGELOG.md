# Ändringslogg

Alla märkbara ändringar i Tandem. Avsnittet för en version blir texten i dess GitHub Release
(`release.yml` kräver ett avsnitt för versionen, se [docs/RELEASE.md](docs/RELEASE.md)).
Rubriken är `## [X.Y.Z] – ÅÅÅÅ-MM-DD`.

## [Unreleased]

## [0.3.0] – 2026-10-26

Handla och planera med Tandem (Fas 2, "handla & planera"): hushållets inköpslista och veckans
matsedel fungerar nu i appen, offline och mellan flera telefoner, så att Kitshn inte längre
behövs. Receptredigering, import av recept, widgets och skafferi kommer i Fas 3 och senare.

- **Handla:** inköpslistan grupperad efter butikens avdelningar (välj butik och lista), avbockade
  varor samlas hopfällda längst ned och "Köp senare" har en egen del. Bocka av med ett tryck eller
  svep, eller alla i en avdelning på en gång, och ångra direkt. Lägg till varor med fritext ("2 dl
  grädde, mjölk") och se hur de tolkas innan de läggs till, även offline; förslag och tidigare
  köpta varor ur historiken (avbockade varor som rensats eller som Tandoor inte längre visar)
  läggs till med ett tryck. Lång tryckning ändrar mängd, enhet, vara och avdelning.
  Överst står vilka andra i hushållet som delar listan ("Delad med: Anna"), också i Veckan.
  Butiksläget har stor text, stora rader och tänd skärm, och listan kan delas som text. På
  surfplatta står "Lägg till", listor och butiker i en sidopanel bredvid listan, och
  avdelningarna står i två kolumner när det finns plats.
- **Inköpslistan offline och i synk:** det du lägger till, bockar av, ändrar, flyttar mellan
  listor eller skjuter upp ("köp senare") sparas direkt på telefonen och skickas när nätet finns,
  i rätt ordning, också för en vara som skapades och bockades av utan nät. Antalet väntande
  ändringar syns. En vara som någon annan i hushållet har tagit bort under tiden släpps tyst,
  utan felanrop mot servern, även om telefonen bara var offline en kort stund. Medan listan är
  öppen syns de andras ändringar inom en halv minut (var 20:e sekund, eller Tandoors längre
  intervall; har du stängt av den automatiska synken på webben gör appen likadant). Avbockade
  varor som Tandoor inte längre visar sparas i en lokal historik.
- **Lägg på inköpslistan:** i receptet (under ingredienserna), på kokläget sista sida och på
  kvällens måltid under Idag. Arket visar ingredienserna i de portioner du valt; det du har
  hemma är inte ikryssat och märkt "Finns hemma". Välj lista, "Markera alla" eller "Bara det som
  saknas" och lägg till, också offline. Varorna visar vilket recept de kom från, och hela
  receptet tas bort från listan med ett tryck.
- **Veckan:** veckans matsedel från Tandoor, dag för dag med idag först, eller som ett
  veckorutnät på surfplatta. Planera en måltid med ett recept eller bara en titel ("Rester"),
  välj dag, måltidstyp, portioner och anteckning, och lägg ingredienserna på inköpslistan om du
  vill. Flytta en måltid till en annan dag eller måltidstyp, ändra eller ta bort den och ångra
  direkt; dagens måltid kan lagas direkt. Allt fungerar offline. Måltider hamnar på samma dag i
  appen och på webben, även nära midnatt och kring sommartidens slut. Ändrar någon annan samma
  måltid under tiden skickas bara det du ändrade, och har ni ändrat samma sak får du välja vems
  ändring som gäller.
- **Handla för veckan:** välj vilka dagar, se alla planerade måltiders ingredienser ihopslagna
  per vara och enhet och sorterade efter butikens avdelningar, med det du har hemma bortvalt.
  Lämna en måltid utanför med ett tryck. En måltid som redan finns på listan läggs aldrig till
  igen, oavsett om Tandoor själv lade dit den (inställningen att lägga till planerade måltider
  automatiskt) eller om appen gjorde det.
- **Receptböcker, favoriter och "Vill laga":** Recept-fliken har Favoriter, Vill laga och
  Böcker överst, och Mer har raden Receptböcker. Favoriter är din egen favoritbok i Tandoor,
  som skapas första gången du trycker på hjärtat och delas för läsning med de andra i
  hushållet; vyn Favoriter visar allas. "Vill laga" är en gemensam kö som alla kan fylla på. I
  receptet finns Favorit, Vill laga och Lägg i bok; bara dina egna böcker kan ändras. Kitshns
  favoritbok räknas som favoriter.
- **Idag:** kvällens måltid har "Lägg på inköpslistan" bredvid "Laga", och raderna "Vill laga"
  och "Favoriter" (hushållets favoriter) står före de andra karusellerna. Är inget planerat leder
  "Planera veckan" till Veckan.
- **Gästläge:** är du gäst i hushållets Tandoor är appen skrivskyddad: recept, matsedel,
  inköpslista och kokläge fungerar, men inga ändringar sparas eller skickas, och en banderoll
  förklarar vad en gäst kan göra.
- **Efter granskningen:** surfplatta stående visar också två kolumner och veckorutnätet; rader på
  64 dp; flytta en vara mellan listor; "Planera" i receptet och Vill laga/Favoriter när du väljer
  recept; lång tryckning flyttar en måltid; "Ångra" när ett recept tas bort från listan eller ur
  en bok; en måltid med varor på listan frågar först och "Ångra" lägger tillbaka varorna; andras
  ändringar tar aldrig bort din "Ångra"; Köpt nyligen fylls när du rensar.
- **Konton och servrar:** spara flera Tandoor-konton (även på olika servrar) och byt mellan dem
  under Mer → Konton och servrar; ett konto i taget är aktivt, och varje konto behåller sina
  recept, sin lista och sina osända ändringar på telefonen. Har ett konto ändringar som inte
  skickats visas antalet innan det tas bort, och de kan exporteras först. Kontokortet under Mer
  visar hushållets namn och din roll.
- **Synken skyddar servern och visar vad som väntar:** efter fem "finns inte" eller "nekad" från
  servern på kort tid pausar synken en stund i stället för att fortsätta skicka. Mer → Diagnostik
  visar om synken är blockerad och till när, och hur många ändringar som väntar eller nekats per
  slag med det senaste felet.
- **Demoläget** har en exempellista med två butiker, en veckomatsedel och receptböcker.
- **Uppdateringen:** databasen uppgraderas till version 4 när appen uppdateras; inget som redan
  är sparat ändras, och ändringar som väntar sedan 0.2.0 skickas som vanligt.
- **Säkrare synk (granskningen före 0.3.0):** en ändring som aldrig kan skickas stoppar inte
  längre resten av kön, en vara eller måltid vars svar gick förlorat läggs inte till två gånger,
  att ta bort en måltid tar bort dess varor på listan, ändringar av andra i hushållet skrivs inte
  över utan att du får välja, "Rensa" tar bara bort den visade listans avbockade varor, och en
  gäst skickas aldrig tillbaka till inloggningen av en nekad ändring. Handla, Veckan och Mer
  varnar när kontot inte ligger i något hushåll (då delas varken listan eller matsedeln), och
  Mer → Synk och lagring visar Tandoor-kontots inköpsinställningar, bland annat att avbockade
  varor kan bli "finns hemma" för hela hushållet.
- **Release-röktest:** varje version provar nu också inköpslistan och matsedeln i
  release-bygget på en Android-emulator: partnerns avbockning syns inom 30 sekunder, 20
  ändringar gjorda utan nät hamnar rätt på servern, 15 rader som någon annan raderade medan
  telefonen var offline släpps utan ett enda felanrop, måltider hamnar på rätt dag åt båda
  hållen och "Handla för veckan" ger inga dubbla rader. Dessutom läggs ett recept på listan,
  en måltid flyttas med flyttarket, och ett andra Tandoor-konto läggs till och byts till och
  från: varje konto visar sin egen lista, och en ändring gjord utan nät skickas bara till rätt
  server.

Saknas fortfarande: dra och släpp i matsedeln, en anteckning per vara på listan (Tandoor har
inget sådant fält), notiser när någon annan ändrar listan och att ta bort receptet från en
planerad måltid (byt recept eller ta bort måltiden).
Inte heller med: Tandoor-kontots inköpsinställningar (bland annat att avbockade varor blir
"finns hemma") ändras fortfarande på webben; "Finns redan hemma?" i inköpsarken förklarar dem.
"Rensa" och att ta bort en måltid från dess egen sida har nu "Ångra" som resten av listan och
veckan, och Synk och lagring visar den uppdateringstakt listan faktiskt har (också serverns
minsta intervall).

## [0.2.0] – 2026-10-24

Laga mat med Tandem (Fas 1b, "laga"): kokläge steg för steg, timers som larmar även med låst
skärm och utan nät, och "Klart!" som skriver i Tandoors koklogg. Inköpslista och matsedel
(flikarna Veckan och Handla) kommer i Fas 2.

- **Timers (grunden för kokläget):** flera timers samtidigt som ringer även med låst skärm och
  utan nät, med "+1 min" och "Stopp" i notisen och en nedräkning i statusraden (Live Update på
  Android 16). På Samsung visar notisen en egen nedräkning, eftersom One UI annars bara visar
  klockslaget då timern är klar. Timers överlever omstart av appen och telefonen. Kan också
  lämnas till klockappen.
- **Justera tiden:** en timerknapp eller en tid i texten frågar först efter tiden. − och + ändrar
  den (10 s, 1 min eller 5 min åt gången beroende på längden), och "ca 20–25 minuter" erbjuder
  båda tiderna; 25 minuter är förvalt.
- **Kokläge:** "Börja laga" i receptet och "Laga" på kvällens måltid under Idag öppnar ett steg
  per sida med stegets ingredienser i de valda portionerna (går att ändra), stegtexten i stor
  stil (A−/A+) och knappar för stegets tider; en tid i texten går också att trycka på. Aktiva
  timers syns längst ned med paus, "+1 min" och stopp, och ett ringande larm syns tydligt.
  Skärmen hålls på, sidorna bläddras med svep, knappar eller tangentbordets pilar, och kokläget
  kan vara mörkt jämt. Första timern frågar om notiser med en förklaring. På surfplatta och i
  liggande läge står ingredienserna och timerna bredvid steget; en vikbar telefon på bordet
  visar steget upptill och timerna nedtill. Kan larmet bli tyst (notiser avstängda) eller sent
  (exakta larm inte tillåtna) säger kokläget det och leder till rätt inställning.
- **Från timern tillbaka till receptet:** tryck på en timernotis, på larmet eller på timerraden
  som syns på alla flikar, så öppnas kokläget på timerns steg (eller timerlistan för en timer
  utan recept). Steg och timers finns kvar när du vrider telefonen.
- **"Klart!":** sista sidan sparar receptet i Tandoors koklogg med portioner, betyg och
  kommentar, även offline (skickas senare) och i demoläget.
- **Inställningar:** Mer har fått egna sidor för *Synk och lagring* (hur ofta appen synkar i
  bakgrunden, *Ladda ner alla recept* för offline med uppskattad storlek och förlopp, hur mycket
  appen använder och *Rensa bildcache*), *Utseende* (tema som gäller direkt i hela appen,
  dynamisk färg från bakgrundsbilden, textstorlek i kokläget, kokläget alltid mörkt), *Kokläge
  och timers* (timers i Tandem eller i klockappen) och *Behörigheter* (vad appen får göra, med
  en knapp till rätt ställe i Androids inställningar). På surfplattan visas sidorna bredvid
  listan.
- **Diagnostik:** versioner, uppmätt starttid och tid för den senaste fulla synken, och
  *Exportera logg* (utan lösenord, token, serveradress eller recept) via delningsmenyn.
- **Snabbare start:** appen levereras med en baslinjeprofil, så Android kompilerar starten,
  receptlistan och receptvyn i förväg redan vid installationen.
- **Synkproblem syns:** när servern nekar appen en stund eller synken pausar efter många fel
  visas det överst med *Försök igen*.
- **Tandoor 2.7.1:** inloggning med lösenord fungerar mot Tandoor 2.7 (som tog bort den gamla
  inloggningen för appar), och 2.7.1 är testad: appen kör den som 2.6, utan säkert läge.
  Kräver servern ett steg till vid inloggningen (till exempel att e-postadressen bekräftas)
  visar inloggningen vägen till *Klistra in token i stället*. Tandoor 2.7.0 nekar appar att
  skriva och körs därför i säkert läge; uppgradera servern till 2.7.1.
- **Release-röktest:** varje version provar nu också kokläget i release-bygget på en
  Android-emulator: recept offline i flygplansläge, två timers samtidigt som båda larmar på låst
  skärm, vridning mitt i, "Klart!" med betyg och kommentar som skickas exakt en gång när nätet
  kommer tillbaka, och samma sak i demoläget utan server.
- **Larm och synk efter granskningen:** ett larm låter även om larmkanalen satts till "Tyst"
  eller saknar ljud (och kokläget varnar), när två larm ringer tystnar inte det ena när det andra
  stoppas, "Ringde 18:45" blir kvar, en timer över 24 h stannar i Tandem i klockappsläget, "en
  kvarts liter" blir ingen timer medan "ett dygn" och "30 s" blir det, och *Ladda ner alla
  recept* hämtar om recept som ändrats på servern och når alla bilder. En timer som avbryts
  eller rensas kommer aldrig tillbaka och ringer, inte ens om dess larm redan var på väg.
- **Granskningsrättelser i kokläget och skalet:** stegbilder visas i kokläget (även offline,
  och för recept som laddades ned med 0.1.0: de hämtas om en gång vid första synken);
  ringande timers står alltid synliga bredvid ingredienserna; den vikbara telefonen lägger inget
  över vecket; alla knappar i kokläget är 64 dp; TalkBack säger varje nytt steg och varje
  startad timer; varningen om tyst larm säger rätt orsak och öppnar rätt inställning; "Klart!"
  säger när posten väntar (offline, säkert läge, logga in igen); utloggningen varnar för
  ändringar som inte har skickats; kontokortet visar serverns funktioner; timerraden kortar
  aldrig av tiden, och en timer från demoläget öppnar aldrig ett annat kontos recept.

## [0.1.0] – 2026-10-16

Första versionen med recept (Fas 1a, "läsa recept"): bläddra, sök och läs dina recept från
Tandoor, även utan nät. Kokläge, timers och "Klart!" kommer i 0.2; inköpslista och matsedel
(flikarna Veckan och Handla) i Fas 2.

- **Första synken:** efter inloggningen hämtar appen dina recept med förlopp ("Hämtar 312
  recept…"). Du kan fortsätta medan den pågår; sedan synkar appen i bakgrunden ungefär var 15:e
  minut när det finns nät, och när du öppnar appen.
- **Recept:** alla dina recept i rutnät eller lista, med sökning direkt i telefonen (även på
  delar av ord och utan å/ä/ö, "kram" hittar "Krämig") och samtidigt på servern. Filter på
  nyckelord, livsmedel (recept som innehåller alla valda), tid och betyg och "bara nedladdade",
  sortering på namn, nyast, betyg och senast lagat. Dra nedåt för att uppdatera.
- **Receptvyn:** bild, nyckelord, betyg, tider, hur ofta och när det lagades, beskrivning,
  ingredienser och steg. **Portionerna** går att ändra och minns per recept, och mängderna
  räknas om **exakt som i Tandoors webb** (med dina inställningar för bråk och decimaler,
  rubriker, "ingen mängd", plural och mallar som `{{ scale(100) }}` i stegtexten). Tryck på en
  mängd för att se den i en annan enhet när Tandoor har en omräkning. Bocka av ingredienser
  medan du läser. Länkade delrecept öppnas direkt; "Öppna på webben" öppnar receptet i Tandoor.
- **Offline:** "Ladda ner offline" sparar ett recept med bild på telefonen. Nedladdade, planerade
  och nyligen visade recept hålls uppdaterade automatiskt. Utan nät visas *Offline*, sökningen går mot det som
  finns sparat och recept som inte är nedladdade är nedtonade.
- **Idag:** kvällens måltid från matsedeln (*Ikväll*), resten av dagen och imorgon, och
  karusellerna Dina nedladdningar, Ofta lagade, Nya, Nyligen visade och Inte lagat på länge.
- **Surfplatta:** receptlistan och receptet sida vid sida, ingredienser och steg i två kolumner.
- **Serverversion:** appen kontrollerar Tandoors version och kör i *säkert läge* (läsning
  fungerar, ändringar väntar) på en version som inte är testad. Byter servern version får du en
  notis om du har tillåtit notiser för Tandem; annars visar appen en gång "Tandoor uppdaterades
  till …" (en otestad version visas i stället av bannern för säkert läge).
- **Logga in igen:** godtar Tandoor inte längre enhetens inloggning stoppas synken och en banner
  ber dig logga in igen. Loggar du in som samma användare finns allt kvar.
- **Återställningsskärm:** kan inloggningen eller inställningarna inte läsas visas en skärm med
  *Logga in igen*, *Exportera väntande ändringar* och *Rensa appdata* i stället för en krasch.
- **Demoläge** med 14 egna exempelrecept med bilder (på svenska och engelska), en matsedel och
  kokhistorik, så att allt ovan går att prova utan Tandoor. *Återställ demodata* under Mer.
- Recept du öppnar i appen syns nu också under "Nyligen visade" i Tandoor på webben (appen meddelar Tandoor när du öppnar ett recept).
- **Utloggning** tar bort kontots recept och bilder från telefonen; betyget räknas ur din kokhistorik så att sortering och filter på betyg fungerar offline; *Logga in igen* går att avbryta.
- **Tillgänglighet och surfplatta:** TalkBack läser en ingrediensrad som en post med mängden (bråk som 3/16) och receptkort med namnet först; texter bryts i stället för att klippas vid stor text; på surfplatta rullar ingredienser och steg var för sig och nyckelords- och livsmedelsfiltret är sidopaneler; en misslyckad synk visas i receptlistan; Idag leder inte till den ännu tomma fliken Veckan.
- **Release-röktest:** varje version loggar nu också in, väntar ut första synken, söker, skalar,
  laddar ner ett recept och läser det i flygplansläge, och provar demoläget, med just
  release-bygget på en Android-emulator innan den släpps.

## [0.0.2] – 2026-10-10

- **Rättat:** inloggningen kraschade i release-bygget (0.0.1) när man tryckte på *Logga in*.
  R8, som krymper release-bygget, hade av misstag gjort inloggningskoden oanvändbar, något som
  bara syntes i det minifierade bygget. Regeln som skyddar Tandoor-anropen gäller nu alla
  nuvarande och framtida anrop, så felet kan inte komma tillbaka med nästa funktion.
- **Kraschlogg:** kraschar Tandem sparas en kort logg på telefonen (appversion, Android-version,
  enhetsmodell och var i koden det hände; inga lösenord, token eller webbadresser). Nästa gång
  visar välkomstskärmen och Mer *Tandem kraschade förra gången* med *Visa*, *Kopiera*, *Dela* och
  *Ta bort*. Ingenting skickas automatiskt; bara de tre senaste loggarna sparas.
- **Release-röktest på emulator:** varje version loggar nu in (med lösenord och med inklistrad
  token), överlever processdöd och kallstart och loggar ut med just release-bygget på en
  Android-emulator innan den släpps.
- Ett oväntat fel under inloggningen visar nu ett felmeddelande i stället för att stänga appen.
- **Rättat:** loggade man ut med en inklistrad token stod det bara "Utloggad". Nu får man veta att
  token fortfarande gäller och kan raderas i Tandoor (Inställningar → API).
- *Visa detaljer* visar hela klassnamn, så att en felrapport från ett release-bygge går att spåra.
- **Rättat:** "Det här ser inte ut som Tandoor" kunde ibland visas för en riktig Tandoor när
  adressen kontrollerades (svaret lästes på fel tråd).

## [0.0.1] – 2026-10-10

Första versionen (Fas 0): grunden att bygga vidare på. Inga recept, ingen veckoplan och ingen
inköpslista än; de kommer i nästa version.

- **Anslut till din egen Tandoor** (2.6 testad; 2.0–2.5 och nyare versioner fungerar i säkert
  läge). Ange adressen, även med en undersökväg som `example.com/recipes`, eller klistra in en
  länk från webbläsaren. Tydliga fel för fel adress, självsignerade certifikat, blockerad åtkomst
  och för gamla versioner.
- **Inloggning** med användarnamn och lösenord: lösenordet sparas aldrig, appen skapar en egen
  token för den här enheten. Alternativt kan en token från Tandoor (Inställningar → API) klistras
  in; en inklistrad token raderas aldrig av appen.
- **Krypterad lagring** av konto och token (Android Keystore). Kan lagringen inte läsas visas en
  återställningsskärm i stället för en krasch; tillfälliga Keystore-fel raderar ingenting.
- **Appskal med fem flikar** (Idag, Recept, Veckan, Handla, Mer): bottenrad på telefonen,
  navigationsrail på surfplattan och när telefonen ligger ner.
- **Mer:** konto, server och Tandoor-version (kontrolleras igen när appen öppnas, högst var 15:e
  minut), språk (svenska eller engelska, eller systemets språk), tema (system, ljust, mörkt) och
  Om Tandem med licenser. Utloggning återkallar enhetens egen token om du vill.
- **Demoläge** för den som inte har någon Tandoor än.
- Helt på **svenska och engelska**.
