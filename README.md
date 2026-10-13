# MeshRouter

MeshRouter to **prototyp działającej aplikacji Android** (API 26+, Android 8.0+) i osobne, wyraźnie oznaczone **demo w przeglądarce**. Android tworzy lokalną sieć Wi‑Fi, inne telefony dołączają przez zaproszenie, wymieniają prawdziwe dane urządzeń i mogą wykonać test przesłania danych komórkowych jednego telefonu do hosta. Interfejs ma tryb ciemny/jasny i algorytm wyboru sugerowanego przekaźnika na podstawie pomiarów oraz baterii.

> **Granica techniczna:** To **nie** jest ogólnosystemowy router agregujący internet wielu telefonów. Android `LocalOnlyHotspot` tworzy lokalny hotspot WPA2 z SSID/hasłem nadawanym przez system, **bez** automatycznego udostępniania internetu. Wybrany przekaźnik jest rekomendacją aplikacji, nie przełączeniem fizycznego hosta Wi‑Fi. Wskaźnik w APK pokazuje **sumę indywidualnych pomiarów przepustowości**, nie realną prędkość jednego połączenia połączonego z wielu łączy. Demo w przeglądarce używa danych symulowanych, nie tworzy żadnej sieci. iOS: tylko demo webowe (API hotspotu jest zablokowane dla zwykłych aplikacji iOS).

## Co działa w APK

- Host: `WifiManager.startLocalOnlyHotspot()`; lokalny serwer HTTP na porcie `8787` (chronione tokenem endpointy /hello, /heartbeat, /state, /leave) oraz informacyjna strona pod `/`.
- Uczestnik: dołączenie do sieci przez `WifiNetworkSpecifier` (Android 10+) po zgodzie systemowej, albo `WifiConfiguration` (Android 8–9); połączenie z hostem po lokalnym Wi‑Fi.
- Zaproszenie `MR1:`: udostępnienie SSID, hasła WPA2, IP hosta i losowego tokenu tylko przez wybrany przez użytkownika kanał (np. wiadomość). Traktuj jak hasło; nie publikuj.
- Prawdziwa lista telefonów: nazwa, model, operator, bateria, poziom Wi‑Fi i wyniki indywidualnych pomiarów. Host usuwa urządzenia, które nie zgłaszały się przez 15 s.
- Ręczny test łącza **komórkowego** danego telefonu (~1 MB pobierania + do 256 KB wysyłania, endpointy Cloudflare Speed Test). Jeśli brak danych mobilnych lub endpoint jest niedostępny, test pokazuje błąd — **nigdy nie wstawia wymyślonych wyników w APK**.
- Host może sprawdzić prawdziwe przekazanie 128 KB: wybrany telefon pobiera dane po swoim internecie komórkowym i odsyła je hostowi przez lokalne Wi‑Fi (port `8788`). To **test w obrębie aplikacji**, nie współdzielenie internetu innym programom.
- Transparentny algorytm „złotego środka”: 46% prędkość, 40% bateria (+premia za ładowanie), 9% opóźnienie, 5% zasięg. Rozładowane telefony są karane. Telefon bez wykonanego testu nie zostaje rekomendowany jako przekaźnik.

## Instalacja / APK

Wymaga Androida 8.0+ i działającego Wi‑Fi; do pomiaru i testu przekazania potrzebne są aktywne dane komórkowe. Wersja debug jest podpisana **kluczem debugowym**, nadaje się do testów, nie do wydania w Google Play.

1. Pobierz [MeshRouter-debug.apk — wersja testowa](https://github.com/rejson59/MeshRouter/releases/download/v0.1.0-preview/MeshRouter-debug.apk). Alternatywnie: [Actions → Build Android APK](https://github.com/rejson59/MeshRouter/actions/workflows/android-apk.yml) → zielony przebieg → **Artifacts** → `MeshRouter-debug-apk` (rozpakuj ZIP).
2. Zainstaluj APK na obu telefonach (włącz zezwolenie „Instaluj nieznane aplikacje” dla użytej przeglądarki/menedżera plików). To **build debug**; po zmianie podpisu może wymagać odinstalowania starej wersji przed aktualizacją.
3. Na telefonie A: **Utwórz** → uprawnienia Wi‑Fi/lokalizacji → **Udostępnij zaproszenie**.
4. Na telefonie B: **Dołącz** → **Wklej ze schowka** albo wklej całe `MR1:…` → potwierdź systemowy monit połączenia Wi‑Fi. Telefony muszą być w zasięgu; na niektórych modelach trzeba włączyć Usługi lokalizacji, Wi‑Fi i dane komórkowe w ustawieniach.
5. Na każdym telefonie włącz **Zmierz moje łącze** (test zużywa dane). Host może wybrać **Sprawdź przekaźnik**, jeśli drugi telefon wykonał pomiar.

> Wygenerowany APK pochodzi z workflow na tym branchu. Artefakty GitHub Actions wygasają po 14 dniach i mogą wymagać zalogowania; prerelease udostępnia trwalszy link do pliku.

## Rozwój lokalny

```bash
npm ci
npm run dev                 # demo w przeglądarce (Vite, port 5173)
npm test                    # testy algorytmu
npm run cap:sync            # buduje UI i kopiuje do android/
# Wymaga JDK 17 + Android SDK 34 (np. Android Studio):
cd android && ./gradlew assembleDebug
```

Podgląd produkcyjnego buildu: `npm run build && npm run preview`. Repo ma workflow GitHub Actions, który buduje i udostępnia instalowalny APK debug. **GitHub Pages nie jest jeszcze skonfigurowane**, bo w pierwszym etapie priorytetem była aplikacja i demo (nie strona marketingowa).

## Ograniczenia i bezpieczeństwo

- Hotspot jest **lokalny i zabezpieczony hasłem**, nie otwarty. Android nie pozwala zwykłej aplikacji tworzyć dowolnego otwartego hotspotu/tetheringu i narzuca jego SSID oraz hasło. Informacyjna strona HTTP hosta jest dostępna ręcznie pod `http://<IP-hosta>:8787/`; **nie ma** automatycznego przechwytywania portalu logowania / HTTPS.
- Połączenie między telefonami korzysta z WPA2 i losowego tokenu, ale lokalny protokół HTTP nie ma dodatkowego TLS; nie używaj tej wersji w nieufnej sieci ani nie przekazuj zaproszenia publicznie. Token zmienia się przy każdej nowej siatce. Serwer nie jest otwartym proxy — obsługuje tylko znane endpointy, limit 16 telefonów i limit wielkości żądań.
- Poziom sygnału i możliwości hotspotu różnią się w zależności od producenta Androida. Na starszych urządzeniach połączenie z Wi‑Fi może wymagać ręcznej interwencji. Aplikacja działa w pierwszym planie; wyłączenie aplikacji/hosta kończy sesję.
- Wykorzystanie łączy wszystkich telefonów do routingu **całego ruchu systemowego** wymaga dodatkowego projektu VPN (`VpnService`/tun2socks), koordynacji ruchu i potencjalnie serwera pośredniczącego, a na iOS nie ma równoważnych swobodnie dostępnych API hotspotu. Nie ma tego w tym prototypie.

## Struktura

- `src/` — UI, tryb demonstracyjny, algorytm i cienki mostek Capacitor. Na natywnym Androidzie **brak fallbacku do symulacji**.
- `android/app/src/main/java/com/rejson59/meshrouter/` — natywny plugin Java Android i ograniczony serwer LAN.
- `captive.html` — wizualizacja przyszłej strony informacyjnej, **nie** działający automatyczny captive portal.
- `.github/workflows/android-apk.yml` — testy i budowanie APK debug w GitHub Actions.
