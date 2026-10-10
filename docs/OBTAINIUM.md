# Installera Tandem med Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installerar och uppdaterar appar direkt från
GitHub Releases. Repot `Demacish/tandem` är publikt, så ingen GitHub-token behövs.

## Lägg till appen

1. Installera Obtainium (från dess GitHub-releaser eller IzzyOnDroid).
2. **Lägg till app** → URL: `https://github.com/Demacish/tandem`.
3. Rekommenderade appinställningar:
   - *Filtrera APK:er med reguljärt uttryck:* `tandem-.*-sideload\.apk$`
     (varje release har en APK, `tandem-X.Y.Z-sideload.apk`, plus `SHA256SUMS` och R8-mappningen).
   - *Inkludera förhandsversioner:* av.
4. Installera. Android frågar första gången om Obtainium får installera okända appar.

Taggen heter `vX.Y.Z` och appens versionsnamn `X.Y.Z`, så Obtainium känner igen installerad
version. Releaser skapas av [`release.yml`](../.github/workflows/release.yml), se
[RELEASE.md](RELEASE.md).

Alla releaser signeras med samma nyckel, och `release.yml` stoppar en release vars certifikat
inte matchar det förväntade fingeravtrycket. Android vägrar dessutom installera en uppdatering
som är signerad med en annan nyckel än den installerade appen.

## Debug-varianten (`.dev`)

- Debug-bygget har paketnamnet `casa.moller.tandem.dev` och versionsnamnet `X.Y.Z-dev`. Det kan
  installeras **bredvid** release-appen (`casa.moller.tandem`) utan att dela data med den.
- Det publiceras **inte** som release. CI (`pr.yml`) laddar upp det som artefakten
  `tandem-sideload-debug` på varje körning (nedladdning från Actions kräver GitHub-inloggning),
  och det installeras för hand (filhanterare eller `adb install`). Obtainium följer bara
  release-appen. Debug-byggen signeras med en publik debug-nyckel (`app/debug.p12`, avsiktligt
  inte hemlig), så en nyare debug-APK installeras ovanpå en äldre.
- Torrkörningar av `release.yml` ger `tandem-X.Y.Z-DRYRUN-throwaway-key.apk`, signerad med en
  **engångsnyckel** och med paketnamnet `casa.moller.tandem.dryrun`. Den installeras bredvid den
  riktiga appen och kan aldrig blockera dess uppdateringar; använd den bara för test och
  avinstallera den sedan. Obtainiums regex matchar inte det filnamnet.
- Testa inte synk med skrivningar med ett debug-bygge mot en server som används på riktigt.

## Om det inte fungerar

- **Obtainium hittar ingen release:** kontrollera URL:en och att *Inkludera förhandsversioner*
  är av (releaser är inte förhandsversioner).
- **"Appen installerades inte" vid uppdatering:** APK:n är signerad med en annan nyckel än den
  installerade. Releaser signeras alltid med samma nyckel. (Torrkörnings-APK:er har ett eget
  paketnamn och kan inte orsaka detta.)
- **Du lade till appen när repot var privat, med en token:** tokenen behövs inte längre. Ta bort
  den i Obtainium (*Inställningar → GitHub*) och återkalla den på GitHub.
