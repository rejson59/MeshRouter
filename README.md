# MeshRouter

MeshRouter to **prototyp aplikacji Android** (API 26+, Android 8.0+) i osobne, wyraźnie oznaczone **demo w przeglądarce**. Android tworzy lokalną sieć Wi‑Fi, telefony dołączają przez zaproszenie, wymieniają prawdziwe dane urządzeń, testują łącza i — za zgodą każdego użytego telefonu — mogą pobrać plik w częściach przez niezależne połączenia komórkowe. Host składa, oblicza SHA-256 i zapisuje plik. Interfejs ma tryb ciemny/jasny i algorytm wyboru sugerowanego przekaźnika na podstawie pomiarów oraz baterii. **Nową funkcję przetestowano automatycznie, ale nie potwierdzono jeszcze na dwóch fizycznych telefonach.**

> **Granica techniczna:** To **nie** jest ogólnosystemowy router agregujący internet wielu telefonów. Android `LocalOnlyHotspot` tworzy lokalny hotspot WPA2 z SSID/hasłem nadawanym przez system, **bez** automatycznego udostępniania internetu. Wybrany przekaźnik jest rekomendacją aplikacji, nie przełączeniem fizycznego hosta Wi‑Fi. Wskaźnik w APK pokazuje **sumę indywidualnych pomiarów przepustowości**, nie realną prędkość jednego połączenia połączonego z wielu łączy. Demo w przeglądarce używa danych symulowanych, nie tworzy żadnej sieci. iOS: tylko demo webowe (API hotspotu jest zablokowane dla zwykłych aplikacji iOS).

## Strona i demo

Strona z opisem aplikacji, linkiem do aktualnego APK i **osadzonym interaktywnym demo** jest gotowa do podglądu (`npm run build:site && npm run site:preview`). Docelowy adres po aktywowaniu GitHub Pages to [**rejson59.github.io/MeshRouter/**](https://rejson59.github.io/MeshRouter/), a pełne demo będzie pod [`/demo/`](https://rejson59.github.io/MeshRouter/demo/). Strona jest statyczna; demo tworzy wyłącznie symulowane urządzenia i pomiary. Tylko instalowana aplikacja Android korzysta z natywnych sieci i pobierania plików. W ramce pulpit demo otwiera się automatycznie; samodzielnie otwarta wersja startuje od ekranu „Utwórz/Dołącz”.

## Co działa w APK

- Host: `WifiManager.startLocalOnlyHotspot()`; lokalny serwer HTTP na porcie `8787` (chronione tokenem endpointy /hello, /heartbeat, /state, /leave) oraz informacyjna strona pod `/`.
- Uczestnik: dołączenie do sieci przez `WifiNetworkSpecifier` (Android 10+) po zgodzie systemowej, albo `WifiConfiguration` (Android 8–9); połączenie z hostem po lokalnym Wi‑Fi.
- Zaproszenie `MR1:`: udostępnienie SSID, hasła WPA2, IP hosta i losowego tokenu tylko przez wybrany przez użytkownika kanał (np. wiadomość). Traktuj jak hasło; nie publikuj.
- Prawdziwa lista telefonów: nazwa, model, operator, bateria, poziom Wi‑Fi i wyniki indywidualnych pomiarów. Host usuwa urządzenia, które nie zgłaszały się przez 15 s.
- Ręczny test łącza **komórkowego** danego telefonu (~1 MB pobierania + do 256 KB wysyłania, endpointy Cloudflare Speed Test). Jeśli brak danych mobilnych lub endpoint jest niedostępny, test pokazuje błąd — **nigdy nie wstawia wymyślonych wyników w APK**.
- Host może sprawdzić prawdziwe przekazanie 128 KB: wybrany telefon pobiera dane po swoim internecie komórkowym i odsyła je hostowi przez lokalne Wi‑Fi (port `8788`). Ten test również wymaga zgody uczestnika i zużywa jego limit danych.
- **Wspólne pobieranie plików (nowość 0.2-preview):** host zleca fragmenty publicznego pliku HTTPS telefonom, które osobno włączyły przekazywanie danych komórkowych. Host może być jednym z dostawców tylko po własnym włączeniu zgody. Wynik jest składany według zakresów bajtów, obliczane jest SHA-256 i opcjonalnie porównywane z podaną sumą; ukończony plik zapisuje się w Pobranych i można go udostępnić. To transfer **tylko w obrębie aplikacji**.
- Transparentny algorytm „złotego środka”: 46% prędkość, 40% bateria (+premia za ładowanie), 9% opóźnienie, 5% zasięg. Rozładowane telefony są karane. Telefon bez wykonanego testu nie zostaje rekomendowany jako przekaźnik.

## Jak działa wspólne pobieranie

1. Host tworzy siatkę, pozostałe telefony dołączają przez `MR1:`. Na **każdym telefonie, którego internet ma zostać użyty**, właściciel osobno zaznacza zgodę i wybiera limit 5–100 MiB na sesję. Domyślnie zgoda jest **wyłączona** (także u hosta). Do transmisji potrzebne są równocześnie lokalne Wi‑Fi i aktywne dane komórkowe. Właściciel może odwołać zgodę w dowolnym momencie; bieżący odczyt może wymagać chwili, żeby się przerwać.
2. Host wkleja publiczny URL `https://…` do pliku o wielkości 1 B–32 MiB. Serwer musi poprawnie obsługiwać `Range` i odpowiedź `206 Partial Content` z `Content-Range`; przekierowania są dozwolone tylko na publiczne HTTPS. Warto wkleić znany SHA-256 — **bez oczekiwanej sumy aplikacja tylko oblicza hash, nie może potwierdzić autentyczności zawartości**.
3. Host dzieli plik na fragmenty do 512 KiB. Każdy chętny telefon pobiera po swoim komórkowym `Network` i oddaje bajty hostowi przez lokalne Wi‑Fi. Widać postęp, rzeczywistych dostawców i limit; nieudane fragmenty są ponawiane przez dostępne telefony. Dwa lub więcej dostępnych łączy może pracować równolegle, ale nie gwarantuje to wzrostu szybkości. Przy jednym łączu transfer jest pojedynczy.
4. Po sprawdzeniu zakresów, rozmiaru i opcjonalnego oczekiwanego SHA-256 plik trafia do `Pobrane/MeshRouter/` (Android 10+) lub katalogu aplikacji `Pobrane` (Android 8–9). Host może go udostępnić systemowym arkuszem. Można też anulować zadanie — niekompletne fragmenty są usuwane.

Limit jest liczony dla **bajtów treści faktycznie odczytanych przez aplikację** (także ponownych prób i 1 bajtu metadanych), nie dla całego ruchu operatora: nagłówki/TLS, DNS i narzut sieci mogą zużyć dodatkowe dane. Zgoda oraz licznik są zerowane po opuszczeniu siatki. Nie podawaj poufnych URL-i ani haseł: adres jest widoczny dla telefonów uczestniczących w pobieraniu, a lokalna kontrola działa bez osobnego TLS. Sprawdź koszty transmisji u operatora. Pobieranie nie routuje ruchu innych aplikacji i nie jest systemowym VPN/bondingiem.

Przed deklaracją działania na konkretnych modelach Androida wykonaj [plan testów na fizycznych urządzeniach](docs/DEVICE_TESTING.md). Wyników tych testów jeszcze nie ma.

## Instalacja / APK

Wymaga Androida 8.0+ i działającego Wi‑Fi; do pomiaru i testu przekazania potrzebne są aktywne dane komórkowe. Wersja debug jest podpisana **kluczem debugowym**, nadaje się do testów, nie do wydania w Google Play.

1. Pobierz [MeshRouter-debug.apk — 0.2.0-preview, wspólne pobieranie](https://github.com/rejson59/MeshRouter/releases/download/v0.2.0-preview/MeshRouter-debug.apk). [Starsza wersja 0.1.0](https://github.com/rejson59/MeshRouter/releases/tag/v0.1.0-preview) nie ma funkcji pobierania pliku. Alternatywnie: [Actions → Build Android APK](https://github.com/rejson59/MeshRouter/actions/workflows/android-apk.yml) → zielony przebieg → **Artifacts** → `MeshRouter-debug-apk` (rozpakuj ZIP).
2. Zainstaluj APK na obu telefonach (włącz zezwolenie „Instaluj nieznane aplikacje” dla użytej przeglądarki/menedżera plików). To **build debug** z tymczasowym kluczem CI: instalacja nowej wersji na starej prawdopodobnie wymaga odinstalowania poprzedniego APK i usuwa dane aplikacji. Zapisane w publicznych Pobranych pliki możesz zachować osobno.
3. Na telefonie A: **Utwórz** → uprawnienia Wi‑Fi/lokalizacji → **Udostępnij zaproszenie**.
4. Na telefonie B: **Dołącz** → **Wklej ze schowka** albo wklej całe `MR1:…` → potwierdź systemowy monit połączenia Wi‑Fi. Telefony muszą być w zasięgu; na niektórych modelach trzeba włączyć Usługi lokalizacji, Wi‑Fi i dane komórkowe w ustawieniach.
5. **Zmierz moje łącze** uruchamia oddzielny test zużywający ok. 1,3 MB (opcjonalny). Aby sprawdzić przekaźnik lub pobrać plik, na używanych telefonach otwórz **Wspólne pobieranie pliku** i włącz zgodę z limitem. Host podaje URL HTTPS oraz (najlepiej) oczekiwany SHA-256 i wybiera **Pobierz przez telefony**. Wskaźnik kołowy nadal pokazuje sumę oddzielnych pomiarów, a postęp rzeczywistego pliku widać w karcie pobierania.

> Wygenerowany APK pochodzi z workflow na tym branchu. Artefakty GitHub Actions wygasają po 14 dniach i mogą wymagać zalogowania; prerelease udostępnia trwalszy link do pliku.

## Rozwój lokalny

```bash
npm ci
npm run dev                 # samodzielne demo w przeglądarce (port 5173)
npm test                    # testy algorytmu i granicy demo/Android
npm run build:site          # strona + identyczne demo pod /demo/ → site-dist/
npm run site:preview        # podgląd całej strony po build:site (port 4173)
npm run cap:sync            # tylko UI aplikacji (dist/) → Android; nie kopiuje strony
# Wymaga JDK 17 + Android SDK 34 (np. Android Studio):
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`npm run build` / `npm run cap:sync` zachowują `dist/index.html` jako wejście aplikacji Android. Oddzielny `npm run build:site` składa `landing.html` jako stronę główną i kopiuje tę samą kompilację demo do `site-dist/demo/`. Publikacja przez `.github/workflows/pages.yml` używa GitHub Pages z gałęzi `arena/b8909b4b-meshrouter` (źródło: GitHub Actions), bez gałęzi `gh-pages`. **Jednorazowo właściciel repozytorium musi włączyć Settings → Pages → Build and deployment → GitHub Actions.** Jeśli Pages jest wyłączone, workflow nadal testuje stronę i udostępnia jej artefakt, lecz nie udaje udanej publikacji; po włączeniu ponowny push na tę gałąź uruchomi wdrożenie. Osobny workflow buduje i udostępnia testowe APK.

## Ograniczenia i bezpieczeństwo

- Hotspot jest **lokalny i zabezpieczony hasłem**, nie otwarty. Android nie pozwala zwykłej aplikacji tworzyć dowolnego otwartego hotspotu/tetheringu i narzuca jego SSID oraz hasło. Informacyjna strona HTTP hosta jest dostępna ręcznie pod `http://<IP-hosta>:8787/`; **nie ma** automatycznego przechwytywania portalu logowania / HTTPS.
- Połączenie między telefonami korzysta z WPA2 i losowego tokenu, ale lokalny protokół HTTP nie ma dodatkowego TLS; nie używaj tej wersji w nieufnej sieci ani nie przekazuj zaproszenia publicznie. Token zmienia się przy każdej nowej siatce. Serwer nie jest otwartym proxy — obsługuje tylko znane endpointy, limit 16 telefonów i limit wielkości żądań.
- Poziom sygnału i możliwości hotspotu różnią się w zależności od producenta Androida. Na starszych urządzeniach połączenie z Wi‑Fi może wymagać ręcznej interwencji. Aplikacja działa w pierwszym planie; wyłączenie aplikacji/hosta kończy sesję.
- Wykorzystanie łączy wszystkich telefonów do routingu **całego ruchu systemowego** wymaga dodatkowego projektu VPN (`VpnService`/tun2socks), koordynacji ruchu i potencjalnie serwera pośredniczącego, a na iOS nie ma równoważnych swobodnie dostępnych API hotspotu. Nie ma tego w tym prototypie.

## Struktura

- `src/` — UI, tryb demonstracyjny, algorytm i cienki mostek Capacitor. Na natywnym Androidzie **brak fallbacku do symulacji**.
- `android/app/src/main/java/com/rejson59/meshrouter/` — natywny plugin Java Android i ograniczony serwer LAN.
- `captive.html` — wizualizacja przyszłej strony informacyjnej, **nie** działający automatyczny captive portal.
- `landing.html`, `src/site/`, `vite.site.config.js`, `scripts/` — strona główna i oddzielny build GitHub Pages; aplikacja nadal używa `index.html`.
- `.github/workflows/pages.yml` — testy, pakowanie strony z `/demo/` i publikacja na GitHub Pages.
- `.github/workflows/android-apk.yml` — testy i budowanie APK debug w GitHub Actions.
