# Tandem på klockan (Wear OS)

Tandem finns också för klockor med Wear OS: inköpslistan att bocka av på klockan, timrarna från
kokläget och en ruta (tile) med det som är kvar att köpa. Klockan har en **egen nyckel** till din
Tandoor och hämtar listan själv över Wi-Fi eller mobilnät, eller genom telefonens anslutning när
de är nära varandra. Du kan alltså bocka av i butiken med telefonen avstängd; avbockningarna väntar
på klockan och skickas när den har nät.

## Vilka klockor

- **Wear OS 5 eller senare** (Android 14 på klockan). Samsung Galaxy Watch 4 och senare (efter
  uppdateringen till One UI 6 Watch), Pixel Watch och andra klockor med Wear OS 5.
- Äldre Samsung-klockor med Tizen fungerar inte.
- Telefonen behöver Google Play-tjänster och klockans app (till exempel Galaxy Wearable eller Pixel
  Watch). Utan dem visar Tandem ingen klocka under Mer.

## Installera på klockan

Obtainium kan inte installera på en klocka, och klockappen finns inte i Play Butik. Installera den
från en dator med `adb` (Android Debug Bridge, ingår i Googles
[SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)).

1. Hämta `tandem-wear-X.Y.Z.apk` från samma release som telefonappen under
   [Releases](https://github.com/Demacish/tandem/releases). Kontrollera gärna summan mot
   `SHA256SUMS` i releasen. Klockappen och telefonappen ska ha **samma version**.
2. Slå på utvecklarläget på klockan: Inställningar → Om klockan → Programvara (eller
   Programvaruinformation) → tryck sju gånger på **Programversion** (Build number).
3. Slå på **Felsökning via ADB** och **Trådlös felsökning** under Inställningar →
   Utvecklaralternativ. Klockan och datorn ska vara på samma Wi-Fi.
4. Under Trådlös felsökning → **Para ihop ny enhet** visar klockan en IP-adress, en port och en
   kod. På datorn:

   ```sh
   adb pair 192.0.2.10:37099      # adressen och porten för ihopparning, sedan koden
   adb connect 192.0.2.10:41235   # adressen och porten som står överst under Trådlös felsökning
   adb devices                    # klockan ska stå med
   adb install tandem-wear-X.Y.Z.apk
   ```

   (Adresserna ovan är bara exempel; använd de som klockan visar.)
5. Slå av Trådlös felsökning (och gärna utvecklarläget) igen när du är klar.

## Uppdatera

Gör samma sak med den nya versionens `tandem-wear-X.Y.Z.apk`: `adb install -r
tandem-wear-X.Y.Z.apk` (`-r` behåller kopplingen och listan). Uppdatera klockan och telefonen till
samma version. Releaserna signeras med samma nyckel som telefonappen, och Android vägrar installera
en APK med en annan nyckel ovanpå den som finns.

## Koppla klockan

1. Ha klockan nära telefonen (Bluetooth på) och öppna Tandem på telefonen.
2. **Mer → Klockan** (raden syns när telefonen ser en klocka med Wear OS) → **Koppla klockan**.
3. Telefonen skapar en ny nyckel i Tandoor bara för klockan (läsa och skriva, gäller i 90 dagar)
   och skickar den direkt till klockan. Klockan hämtar listan.

Bra att veta:

- Nyckeln skickas bara till en klocka **i närheten**, aldrig via molnet, en säkerhetskopia eller en
  logg. Telefonens egen nyckel lämnar aldrig telefonen.
- Telefonen **förnyar** klockans nyckel när det är mindre än 30 dagar kvar, nästa gång Tandem
  startar med klockan i närheten. Har klockan inte varit nära på länge slutar nyckeln gälla; då
  visar klockan "Logga in igen på telefonen", och du kopplar den igen.
- Klockan hör till **ett** konto: det som är aktivt på telefonen när du kopplar. Byter du konto på
  telefonen fortsätter klockan med det gamla tills du kopplar den igen.
- Det går inte i demoläget, och inte för en gäst (Tandoor låter inte en gäst bocka av).
- Klockans nyckel syns i Tandoors webb under Inställningar → API som en vanlig token med
  `read write`. Ta inte bort den där om du vill fortsätta använda klockan.

## På klockan

- **Listan** följer butikens ordning (den första butiken med en ordning, tryck på butiken längst
  ned för att byta). Samma vara står på en rad; ett tryck bockar av alla dess poster. Avbockat
  hamnar längst ned och går att ångra.
- **Utan nät** sparas avbockningarna på klockan och skickas när klockan har nät igen, också om du
  stänger appen eller startar om klockan.
- Svarar servern inte väntar klockan (5 minuter, sedan längre, högst 2 timmar), och ber Tandoor den
  vänta gör den det. Godtas inte nyckeln längre slutar klockan direkt att fråga.
- **Timrarna** från kokläget visas överst och som pågående aktivitet på urtavlan. När en timer är
  klar vibrerar klockan; **Stoppa** på klockan stoppar larmet på telefonen också, och tvärtom.
  Klockan frågar efter lov att visa aviseringar första gången du trycker på Tillåt vid timrarna.
- **Rutan** "Inköpslista" visar hur mycket som är kvar, de första varorna och nästa timer; tryck
  för att öppna listan.

## Koppla från

**Mer → Klockan → Koppla från klockan** tar bort klockans nyckel i Tandoor och tömmer klockan.
Är klockan inte i närheten tas nyckeln ändå bort; klockan visar då "Logga in igen" tills du
kopplar den igen. Loggar du ut kontot på telefonen tar klockan bort sin egen nyckel nästa gång den
är nära telefonen.

Har du tappat telefonen: ta bort klockans nyckel i Tandoors webb (Inställningar → API) och rensa
appens data på klockan (Inställningar → Appar → Tandem → Lagring → Rensa data).

## Avinstallera

`adb uninstall casa.moller.tandem` med klockan ansluten tar bort appen från klockan (inte från
telefonen). Koppla från klockan först, så att nyckeln tas bort i Tandoor.
