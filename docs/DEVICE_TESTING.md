# MeshRouter 0.2 — plan testu na fizycznych Androidach

**Status: nieprzeprowadzony.** Build CI i testy jednostkowe nie weryfikują równoczesnego działania Wi‑Fi LocalOnlyHotspot i sieci komórkowej danego producenta. Nie oznaczaj funkcji jako potwierdzonej sprzętowo bez tego testu.

## Przygotowanie

- Dwa telefony Android 8+ (najlepiej również jeden Android 8/9 i jeden Android 10+), aktywne osobne pakiety danych, Wi‑Fi, lokalizacja i uprawnienia aplikacji. Dla rzeczywistej równoległości host **też** włącza zgodę albo dodaj trzeci telefon.
- Publiczny plik 2–5 MiB po HTTPS, którego serwer odpowiada `206 Partial Content`, podaje prawidłowy `Content-Range` i najlepiej silny `ETag`. Oblicz jego SHA-256 niezależnym narzędziem i wprowadź w aplikacji. Nie używaj poufnego URL-a. Zanotuj operatorów i koszty transferu.
- Oba urządzenia mają otwartą aplikację na pierwszym planie. Zapisz model, wersję Androida, czy operator pozwala utrzymać dane komórkowe obok lokalnego Wi‑Fi, wynik i ewentualny komunikat błędu.

## Scenariusze akceptacyjne (wszystkie niezaznaczone)

- [ ] Telefon A tworzy hotspot, telefon B dołącza przez `MR1:`; lista pokazuje **rzeczywiste** urządzenia.
- [ ] Domyślnie zgoda obu telefonów jest wyłączona; bez niej host nie może rozpocząć pobierania. Po jej włączeniu na obu pojawiają się limity i identyfikacja źródeł.
- [ ] Host pobiera plik z poprawnym SHA-256: w postępie pojawiają się **Host** oraz telefon B jako dostawcy bajtów (plik powinien mieć co najmniej dwa fragmenty), pobranie kończy się w `Pobrane/MeshRouter/` i hash zapisanych bajtów jest zgodny.
- [ ] Bez aktywnych danych komórkowych jednego telefonu transfer przełącza fragmenty na dostępny drugi telefon albo kończy się **prawdziwym błędem**; nie udaje sukcesu.
- [ ] Zły oczekiwany SHA-256: plik **nie jest zatwierdzany**, a aplikacja pokazuje błąd.
- [ ] Uczestnik cofa zgodę w trakcie transferu: bieżący odczyt kończy się lub zawodzi, limit nie rośnie ponad wybrany cap bajtów treści, a host kontynuuje na pozostałych źródłach, jeśli mają wystarczający limit.
- [ ] Limit 5 MB dla pliku >5 MB przy braku innych chętnych: transfer kończy się czytelnym błędem bez przekroczenia cap bajtów treści. Protokół/TLS może jednak zużyć dodatkowe dane operatora.
- [ ] Anulowanie i utrata Wi‑Fi: nie ma końcowego uszkodzonego pliku; po ponownym połączeniu stan zgody jest wyłączony.
- [ ] Udostępnianie zapisanego pliku działa przez systemowy arkusz Androida (FileProvider na 8–9, MediaStore na 10+).
- [ ] Na stronie demo żaden przycisk pobierania prawdziwego pliku nie działa, a etykieta wyraźnie opisuje symulację.

Po wykonaniu dołącz wyniki, SHA-256 pliku źródłowego i z urządzenia, pobrane/zużyte bajty (z uwzględnieniem narzutu sieci) i modele telefonów do zgłoszenia GitHub. Nie zamieszczaj tokenu zaproszenia, haseł ani adresów z danymi uwierzytelniającymi.
