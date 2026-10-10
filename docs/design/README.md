# Design – Tandem (Stitch)

Stitch-projekt: "Recept – Tandoor-app". Designsystem: "Recept – varm kök" (ljus) och "(mörk)" – se `stitch-export/recept_varm_k_k*/DESIGN.md`.
Tomat `#C2410C`, saffran `#D97706`, örtgrön `#4D7C0F`, gräddvit `#FBF7F2`; mörkt: `#1C1714` / `#FB8A5C`. Bricolage Grotesque + DM Sans, Material Symbols Rounded.

**`stitch-export/`** är den fullständiga exporten från Stitch (2026-10-08): en mapp per skärm med `screen.png` och `code.html` (Tailwind-HTML, referens för Compose – inte kod som används rakt av). Tre skärmar har bara PNG (HTML-exporten misslyckades).
I exportens PNG:er visas ikonerna ibland som text ("arrow_back", "sync") eftersom ikontypsnittet inte hann laddas vid exporten – inte ett designfel. Tre mörka skärmar saknade bild i exporten och är renderade lokalt från `code.html`. Skärmar hämtade direkt via Stitch-API:t är 780 px breda (bättre än zip-exportens).
Filerna direkt i `docs/design/` (utanför exporten) är tidigare hämtade versioner.

## Inventering

| Skärm | Mapp i `stitch-export/` | Mobil ljus | Mobil mörk | Surfplatta |
|---|---|---|---|---|
| Intro 1 – Välkommen (med "Utforska med demodata") | `tandem_v_lkommen_intro_steg_1_av_3` | ✅ | – | – |
| Intro 2 – Anslut till din Tandoor | `tandem_anslut_till_din_tandoor_intro_steg_2_av_3` | ✅ | – | (bred) |
| Intro 2 – Anslutningsfel (med "Testa med demoläget i stället") | `intro_anslutningsfel` | ✅ | – | – |
| Intro 3 – Logga in | `tandem_logga_in_intro_steg_3_av_3` | ✅ | – | – |
| Första synkningen | `intro_f_rsta_synk` | ✅ | – | – |
| Idag | `idag_*` | ✅ | ✅ | ✅ ×2 |
| Idag i demoläge (banner "Anslut din Tandoor") | `idag_demol_ge` | ✅ | – | – |
| Receptlista | `recept_alla_recept` | ✅ | – | – |
| Sök, filter & samlingar | `recept_s_k_filter_samlingar` | ✅ | – | – |
| Receptvy | `recept_kr_mig_svamppasta*`, `receptvy_*_surfplatta` | ✅ | ✅ | ✅ |
| Ark "Lägg på listan" (bara PNG) | `l_gg_p_listan_ark` | ✅ | – | – |
| Kokläge | `kokl_ge_*` | ✅ | ✅ | ✅ |
| Kokläge "Smaklig måltid!" (klart-sidan, bara PNG) | `kokl_ge_klart` | ✅ | – | – |
| Veckan | `veckan_matsedel*` | ✅ ×2 | ✅ | ✅ |
| Ark "Planera måltid" | `planera_m_ltid_ark` | ✅ | – | – |
| Handla för veckan (sammanställning) | `handla_f_r_veckan` | ✅ | – | – |
| Handla (inköpslista) | `ink_pslista_*` | ✅ | ✅ | ✅ |
| Inköpslista – tomt läge & offline | `ink_pslista_tomt_offline` | ✅ | – | – |
| Butiksläge (bara PNG) | `butiksl_ge` | ✅ | – | – |
| Mer / Inställningar | `mer_inst_llningar` | ✅ | – | – |
| Inställningar – Välj språk | `inst_llningar_v_lj_spr_k` | ✅ | – | – |
| Säkert läge / kompatibilitetsvarning | `tandem_s_kert_l_ge_kompatibilitetsvarning` | ✅ | – | – |
| Import – granska och spara | `importfl_de_granska_och_spara_recept` | ✅ | – | – |
| Hemskärmswidgets | `android_hemsk_rmswidgets` | ✅ | – | – |
| Låsskärm – timer-notiser | `l_ssk_rm_timer_notiser` | – | ✅ | – |
| Idag (surfplatta, mörkt) | `idag_surfplatta_m_rkt_l_ge` | – | – | ✅ mörk |
| Köksskärmsläge | `k_kssk_rmsl_get_ambient_k_kshub` | – | – | ✅ mörk |

**Alla MVP-skärmar från granskningen finns nu.**

### Skärmar för senare faser (zip 4–5, 2026-10-08)
| Fas | Funktion | Mapp |
|---|---|---|
| F1 | #37 Återställningsskärm / felsäkert läge | `terst_llning_fels_kert_l_ge` |
| F1/P | Om, juridik, rapportera problem | `om_tandem_juridik` |
| F2 | #42 Gästläge (skrivskyddat) | `tandem_g_stl_ge_skrivskyddat` |
| F2 | #14 Köp senare | `ink_pslista_k_p_senare_planering` |
| F2 | Konto och servrar | `konto_servrar` |
| F3 | #18/#19 Import – startskärm (länk, foto/PDF, text) | `importera_recept_url_foto_text` |
| F3 | #22 Redigera recept | `redigera_recept_kr_mig_svamppasta` |
| F3 | #24 Näringspanel | `n_ringspanel_kr_mig_svamppasta` |
| F4 | #26/#28 Skafferi + Använd upp | `skafferi_anv_nd_upp_r_varor` |
| F4 | #27 Streckkod | `streckkodsskanning_skafferisynk` |
| F4 | #31 Föreslå veckan + notiser | `f_resl_veckan_notiser` |
| F5 | #32 Handsfree-kokläge | `handsfree_kokl_ge_r_st_gester` |
| F5 | #33 Wear OS | `wear_os_klockapp_brickor` |
| F5 | #35/#36 iCal + RSS-inkorg | `externa_fl_den_ical_rss_inkorg` |
| Surfplatta | Receptbok list-detalj, Mer/Inställningar | `receptbok_master_detail_surfplatta`, `mer_inst_llningar_surfplatta` |
| Vikbar | Kokläge i bordsläge | `kokl_ge_bordsl_ge_vikbar_telefon` |

### Ikon, butiksgrafik och sista luckorna (zip 6, 2026-10-09)
| Funktion | Mapp |
|---|---|
| Appikon 512 px | `tandem_appikon_512x512` |
| Play feature graphic 1024×500 | `google_play_feature_graphic_banner_1024x500_style_landscape_warm_scandinavian` (bara PNG) |
| Play-presentation: ikon, banner, 6 skärmbilder, butikstext, tomma lägen | `google_play_butikspresentation_grafik` |
| Behörigheter med förklaring | `k_kets_beh_righeter_systemguide` |
| #29 Återkommande varor | `terkommande_varor_29` |
| #34 Påminnelse nära butiken | `p_minnelse_n_ra_butik_34` |

### Fortfarande utan egen skärm
- **Tomma lägen** för receptlistan och veckan finns bara som textbeskrivning i Play-presentationen (bra innehåll: importera / ladda exempelrecept resp. "Föreslå veckan", "Kopiera förra veckan") – ritas i bygget med inköpslistans tomma läge som förlaga.
- **Adaptiv ikon:** förgrund/bakgrund separat (108 dp, säker zon 66 dp) och en **monokrom** variant för temade ikoner – görs som vektor i Fas 0 utifrån ikonen.
- **Riktiga butiksskärmbilder** tas från appen i demoläget (Fas P); skisserna är bara matfoton.
- **Mörkt läge** för intro och surfplatta för sök – från designsystemet, inga skisser behövs.

### Granskning av senare-fas-skärmarna (2026-10-08)
- **Säkert läge:** bra innehåll (skrivskydd, kompatibilitetsrapport, "Tillåt experimentell synkning" som opt-in). Versionsnumren är inkonsekventa (bannern säger v1.5.8, kortet v2.8.2) – i appen visas serverns faktiska version.
- **Import:** bra granskningsflöde. "Lägg saknade råvaror på inköpslistan" ska vara **avbockad** som standard; "Extraherat av Tandem AI" → "Tolkat av din Tandoor" (AI-importen sker i Tandoor, PLAN B8).
- **Widgets:** väder finns inte i appen (ingen väder-API) – ta bort; resten (dagens middag, handla, veckan) stämmer med planen.
- **Låsskärm/timers:** stämmer med Live Updates/progress-notiser (Android 16, Samsungs Now Bar). Systemet styr layouten delvis, så bilden är en riktlinje.
- **Köksskärmsläge:** "Kökets att göra · Synkat med AnyList", inne-/utetemperatur och "Bord dukat" finns inte i Tandoor eller planen – ersätt med inköpslistan och dagens/morgondagens middag. "Röst redo" hör till Fas 5.


## Justeringar i skisserna (görs i bygget, inte i Stitch)
- **Namn:** toppfältet säger "Recept" på flera skärmar → **Tandem**.
- **Logga in:** "Kom ihåg mig" och "inloggningsuppgifterna sparas krypterat" stämmer inte – lösenordet sparas aldrig, bara en enhetstoken (§4.2). Text: "Du förblir inloggad på den här enheten. Lösenordet sparas aldrig." Lägg till knappen **"Prova demoläget i stället"** under inloggningsknappen (finns redan på välkomst- och felskärmen).
- **Anslutningsfel:** växeln "Acceptera självsignerat SSL-certifikat" ska **inte** finnas – att stänga av certifikatkontrollen gör token-stöld möjlig. I stället litar appen på certifikat som användaren själv har installerat i Android (user CA via `network_security_config`), och felskärmen förklarar hur man gör.
- **Välj språk:** rubriken säger "Handla För Veckan" (fel) → "Språk". Lägg överst till **"Systemets språk"**. Texterna "Tandoor Core v1.5" och "Översättningar hämtas från Tandoors officiella språkfiler" stämmer inte – appens översättningar ligger i appen (Weblate, §6.7); visa i stället hur komplett översättningen är ("Fullständigt översatt" / "92 % översatt").
- **Idag i demoläge:** chipet "Offline" är missvisande i demoläget → "Demo". "Demodata v1.4" → bara "Demodata".
- **Mer:** "Tandem Kokbok v1.4.2" → "Tandem" + verklig version; demoläget som en rad som leder till demot, inte en växel mitt bland serverinställningarna; "Logga ut från Tandoor Home" använder serverns faktiska namn; språkraden leder till språklistan i stället för en Sv/En-växel.
- **Veckan surfplatta:** söndagskolumnen klipps, några bildplatshållare.
- **Inköpslista surfplatta:** kategorichipsen ska komma från Tandoors butikskategorier, inte vara hårdkodade.
- **Om:** "Licensierad under GNU AGPLv3" stämmer inte – Tandem är licensierat under Apache-2.0. Version "1.4.2 (14208)" är påhittad.
- **Streckkod:** "Kamera aktiv (Tandoor AI)" stämmer inte – skanningen sker i telefonen (ML Kit/ZXing) och produktdata kommer från Open Food Facts (PLAN §8).
- **Handsfree:** "Hej Tandem" med ständig lyssning och kameragester (vifta) finns inte i planen – planen har röstkommandon när kokläget är öppet. Väckningsord och kameragester kräver ständig mikrofon/kamera (batteri, integritet); bör vara opt-in och utvärderas i Fas 5.
- **Wear OS:** "Pixel Watch 3" i skissen – Samsung Galaxy Watch (Wear OS) ska också stödjas och fungerar likadant.
- **iCal:** länken kräver en egen token med scope `mealplan` (PLAN #36); "Kopiera länk" ska varna att den som har länken kan se matsedeln.
- **Gästläge/flera skärmar:** ikoner visas som text ("visibility", "restaurant_menu") – exportfel, inte design.
- **Ikon vs banner:** ikonen är en kastrull med hjärta, men bannerns logga är ett annat märke (ett "T") – välj ett. Kastrullen fungerar bättre som liten ikon och monokrom.
- **Butiksnamn:** skissen säger "Tandem – Recept & Matsedel". För att synas när man söker på Tandoor bör namnet innehålla det, t.ex. "Tandem för Tandoor – Recept" (B19, max 30 tecken). Märket "Google Play Console Godkänd" är påhittat.
- **Butikstexten** lovar geofence, handsfree-gester och Wear OS – ta bara med det som finns i den version som publiceras (Fas P = Fas 0–2).
- **Behörigheter:** toppfältet säger "Receptdetalj" (fel, gäller även #29 och #34). Visa inte rånamn som `SCHEDULE_EXACT_ALARM`. Batteri-kortet visas bara på Samsung och liknande. Behörigheter frågas i sammanhanget (när första timern startas, när kameran öppnas) – skärmen är en översikt i Inställningar, inte ett tvingat steg. "Påminnelser när någon i familjen bockar av varor" finns inte i planen.
- **#29 Återkommande varor:** skissen visar schemalagda varor ("varje vecka", "varannan vecka", automatiskt eller fråga först) medan planen har förslag från köphistoriken. Bra att kombinera: förslag från historiken → användaren gör det till ett schema. Schemat lagras lokalt i appen (Tandoor har inga återkommande varor), "Fråga först" som standard. Produktbilder och "Ligger i varukorgen" finns inte i Tandoor – ta bort.
- **#34 Nära butiken:** kräver bakgrundsplats ("Tillåt alltid"), vilket Google Play granskar särskilt (deklaration + video) och Androids geofencing-API ingår i Google Play-tjänsterna – alltså bara i `play`/`sideload`, inte F-Droid. Butikens plats väljs i appen (Tandoors butiker har ingen adress). Bra: tidsfilter, "kräv oavbockade varor", platsen lämnar aldrig telefonen.
