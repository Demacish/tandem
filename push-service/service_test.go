package main

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// --- Fakes ---

// fakeTandoor answers all_personal per token and the shopping list's counts.
type fakeTandoor struct {
	mu       sync.Mutex
	members  map[string]string // token -> all_personal JSON
	count    int
	changed  int
	requests []string
	status   int
}

func (f *fakeTandoor) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.requests = append(f.requests, r.URL.RequestURI())
	if f.status != 0 {
		w.Header().Set("Retry-After", "120")
		w.WriteHeader(f.status)
		return
	}
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	body, ok := f.members[token]
	if !ok {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"detail":"Authentication credentials were not provided."}`)
		return
	}
	switch r.URL.Path {
	case "/api/user-space/all_personal/":
		_, _ = io.WriteString(w, body)
	case "/api/shopping-list-entry/":
		n := f.count
		if r.URL.Query().Get("updated_after") != "" {
			n = f.changed
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"count": n, "next": nil, "previous": nil,
			"timestamp": "2026-11-05T10:00:00.000000+01:00", "results": []any{},
		})
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (f *fakeTandoor) set(count, changed int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.count, f.changed = count, changed
}

func (f *fakeTandoor) requestCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.requests)
}

func member(space, household int64, group string) string {
	h := "null"
	if household > 0 {
		h = `{"id":` + itoa(household) + `,"name":"Household"}`
	}
	return `[{"id":7,"user":{"id":1},"space":` + itoa(space) + `,"groups":[{"id":2,"name":"` + group + `"}],"household":` + h + `,"active":true}]`
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

// fakeEndpoint records the wake-ups it receives.
type fakeEndpoint struct {
	mu      sync.Mutex
	bodies  [][]byte
	headers []http.Header
	status  int
}

func (f *fakeEndpoint) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	f.mu.Lock()
	f.bodies = append(f.bodies, body)
	f.headers = append(f.headers, r.Header.Clone())
	status := f.status
	f.mu.Unlock()
	if status == 0 {
		status = http.StatusCreated
	}
	w.WriteHeader(status)
}

func (f *fakeEndpoint) header(i int) http.Header {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.headers[i]
}

func (f *fakeEndpoint) setStatus(status int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.status = status
}

func (f *fakeEndpoint) received() [][]byte {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][]byte{}, f.bodies...)
}

type harness struct {
	tandoor  *fakeTandoor
	endpoint *fakeEndpoint
	pushURL  string
	api      *API
	server   *httptest.Server
	poller   *Poller
	store    *Store
	now      time.Time
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	ft := &fakeTandoor{members: map[string]string{
		"operator": member(1, 2, "admin"),
		"partner":  member(1, 2, "user"),
		"guest":    member(1, 2, "guest"),
		"outsider": member(1, 3, "user"),
	}}
	tandoorServer := httptest.NewServer(ft)
	t.Cleanup(tandoorServer.Close)
	fe := &fakeEndpoint{}
	pushServer := httptest.NewServer(fe)
	t.Cleanup(pushServer.Close)

	base, _ := url.Parse(tandoorServer.URL)
	cfg := Config{
		TandoorURL: base, TandoorToken: "operator", Poll: 30 * time.Second, AllowHTTPEndpoints: true,
		MaxEndpoints: 3, RegistrationTTL: 60 * 24 * time.Hour, TestPush: true,
	}
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	h := &harness{tandoor: ft, endpoint: fe, pushURL: pushServer.URL + "/upTopic?up=1", store: store, now: time.Date(2026, 11, 5, 9, 0, 0, 0, time.UTC)}
	clock := func() time.Time { return h.now }
	tandoor := NewTandoor(base, http.DefaultClient, "test")
	pusher := &Pusher{client: http.DefaultClient, log: log}
	household, err := operatorHousehold(context.Background(), tandoor, "operator", log)
	if err != nil {
		t.Fatal(err)
	}
	h.poller = &Poller{cfg: cfg, tandoor: tandoor, store: store, pusher: pusher, log: log, now: clock, household: household}
	h.api = &API{
		cfg: cfg, version: "test", tandoor: tandoor, store: store, poller: h.poller, pusher: pusher,
		household: household, log: log, now: clock,
		limiter: NewRateLimiter(1000, 1000), verifier: NewRateLimiter(1000, 1000), lastTest: map[string]time.Time{},
	}
	h.server = httptest.NewServer(h.api.Routes())
	t.Cleanup(h.server.Close)
	return h
}

func (h *harness) post(t *testing.T, path, token string, body any) (int, map[string]any) {
	t.Helper()
	data, _ := json.Marshal(body)
	req, _ := http.NewRequest(http.MethodPost, h.server.URL+path, bytes.NewReader(data))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	out := map[string]any{}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// --- Configuration ---

func TestConfigNeedsUrlAndToken(t *testing.T) {
	_, err := LoadConfig(func(string) string { return "" })
	if err == nil || !strings.Contains(err.Error(), "TANDOOR_URL") || !strings.Contains(err.Error(), "TANDOOR_TOKEN") {
		t.Fatalf("expected both to be required, got %v", err)
	}
}

func TestConfigReadsValuesAndRejectsTooFastPolling(t *testing.T) {
	env := map[string]string{"TANDOOR_URL": "https://recipes.example.com/", "TANDOOR_TOKEN": "x", "POLL_SECONDS": "60", "ALLOW_HTTP_ENDPOINTS": "true"}
	cfg, err := LoadConfig(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	if cfg.TandoorURL.String() != "https://recipes.example.com" || cfg.Poll != time.Minute || !cfg.AllowHTTPEndpoints || cfg.ListenAddr != ":8080" {
		t.Fatalf("unexpected config %+v", cfg)
	}
	env["POLL_SECONDS"] = "5"
	if _, err := LoadConfig(func(k string) string { return env[k] }); err == nil {
		t.Fatal("5 s polling must be rejected")
	}
}

// --- Tandoor client ---

func TestMembershipReadsSpaceHouseholdAndGuest(t *testing.T) {
	h := newHarness(t)
	m, err := h.poller.tandoor.Membership(context.Background(), "guest")
	if err != nil || m.Space != 1 || m.Household == nil || *m.Household != 2 || !m.Guest {
		t.Fatalf("unexpected membership %+v %v", m, err)
	}
	if _, err := h.poller.tandoor.Membership(context.Background(), "nobody"); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("expected ErrUnauthorized, got %v", err)
	}
	if got := (Membership{Space: 4}).HouseholdKey(); got != "s4-h0" {
		t.Fatalf("household key without a household: %s", got)
	}
}

func TestSnapshotEncodesUpdatedAfter(t *testing.T) {
	h := newHarness(t)
	if _, err := h.poller.tandoor.Snapshot(context.Background(), "operator", "2026-11-05T10:00:00.000000+01:00"); err != nil {
		t.Fatal(err)
	}
	h.tandoor.mu.Lock()
	last := h.tandoor.requests[len(h.tandoor.requests)-1]
	h.tandoor.mu.Unlock()
	if !strings.Contains(last, "updated_after=2026-11-05T10%3A00%3A00.000000%2B01%3A00") || !strings.Contains(last, "page_size=1") {
		t.Fatalf("updated_after not encoded: %s", last)
	}
}

func TestRetryAfterIsHonouredByTheBackoff(t *testing.T) {
	b := Backoff{Interval: 30 * time.Second}
	if got := b.Next(&RetryAfterError{Wait: 10 * time.Minute}); got != 10*time.Minute {
		t.Fatalf("expected 10 min, got %s", got)
	}
	if got := b.Next(errors.New("boom")); got != 2*time.Minute {
		t.Fatalf("expected 30 s × 2² = 2 min, got %s", got)
	}
	if got := b.Next(ErrUnauthorized); got != maxBackoff {
		t.Fatalf("a rejected token waits the maximum, got %s", got)
	}
	if got := b.Next(nil); got != 30*time.Second || b.Open() {
		t.Fatalf("a success closes the breaker, got %s", got)
	}
	if parseRetryAfter("", time.Now()) != time.Minute || parseRetryAfter("99999", time.Now()) != time.Hour {
		t.Fatal("Retry-After defaults and caps")
	}
}

// --- Registration API ---

func TestRegisterChecksTheAccount(t *testing.T) {
	h := newHarness(t)
	body := map[string]string{"endpoint": h.pushURL}
	if code, out := h.post(t, "/v1/register", "", body); code != http.StatusUnauthorized || out["error"] != "unauthorized" {
		t.Fatalf("no token: %d %v", code, out)
	}
	if code, _ := h.post(t, "/v1/register", "nobody", body); code != http.StatusUnauthorized {
		t.Fatalf("rejected token: %d", code)
	}
	if code, out := h.post(t, "/v1/register", "outsider", body); code != http.StatusForbidden || out["error"] != "other_household" {
		t.Fatalf("other household: %d %v", code, out)
	}
	if code, out := h.post(t, "/v1/register", "guest", body); code != http.StatusForbidden || out["error"] != "guest" {
		t.Fatalf("guest: %d %v", code, out)
	}
	if h.store.Len() != 0 {
		t.Fatal("nothing may be stored for rejected phones")
	}
	code, out := h.post(t, "/v1/register", "partner", body)
	if code != http.StatusOK || out["id"] == "" || out["poll_seconds"] != float64(30) {
		t.Fatalf("partner: %d %v", code, out)
	}
	// Registering again keeps the id.
	_, again := h.post(t, "/v1/register", "partner", body)
	if again["id"] != out["id"] {
		t.Fatalf("the id changed: %v -> %v", out["id"], again["id"])
	}
}

func TestARegisteredPhoneRenewsWithItsIdInsteadOfTheToken(t *testing.T) {
	h := newHarness(t)
	body := map[string]string{"endpoint": h.pushURL}
	_, out := h.post(t, "/v1/register", "partner", body)
	id, _ := out["id"].(string)
	seen, _ := h.store.Get(h.pushURL)

	h.now = h.now.Add(time.Hour)
	code, again := h.post(t, "/v1/register", "", map[string]string{"endpoint": h.pushURL, "id": id})
	if code != http.StatusOK || again["id"] != id {
		t.Fatalf("renewal by id: %d %v", code, again)
	}
	renewed, _ := h.store.Get(h.pushURL)
	if !renewed.LastSeen.After(seen.LastSeen) {
		t.Fatalf("last seen did not move: %v -> %v", seen.LastSeen, renewed.LastSeen)
	}

	// Another id is no proof, and neither is an id for an endpoint the service does not have.
	if code, out := h.post(t, "/v1/register", "", map[string]string{"endpoint": h.pushURL, "id": "wrong"}); code != http.StatusUnauthorized {
		t.Fatalf("wrong id: %d %v", code, out)
	}
	other := h.pushURL + "&n=2"
	if code, out := h.post(t, "/v1/register", "", map[string]string{"endpoint": other, "id": id}); code != http.StatusUnauthorized {
		t.Fatalf("unknown endpoint: %d %v", code, out)
	}
}

func TestRegisterRejectsBadEndpointsAndKeys(t *testing.T) {
	h := newHarness(t)
	for _, endpoint := range []string{"", "ftp://example.com/x", "https://user:pw@example.com/x", "not a url"} {
		if code, out := h.post(t, "/v1/register", "partner", map[string]string{"endpoint": endpoint}); code != http.StatusBadRequest || out["error"] != "invalid_endpoint" {
			t.Fatalf("%q: %d %v", endpoint, code, out)
		}
	}
	if validEndpoint("http://push.example.com/x", false) || !validEndpoint("https://push.example.com/x?up=1", false) {
		t.Fatal("http needs ALLOW_HTTP_ENDPOINTS, https always works")
	}
	code, out := h.post(t, "/v1/register", "partner", map[string]string{"endpoint": h.pushURL, "p256dh": "abc", "auth": "def"})
	if code != http.StatusBadRequest || out["error"] != "invalid_keys" {
		t.Fatalf("bad keys: %d %v", code, out)
	}
}

func TestRegistrationsAreCapped(t *testing.T) {
	h := newHarness(t)
	for i := 0; i < 3; i++ {
		if code, _ := h.post(t, "/v1/register", "partner", map[string]string{"endpoint": h.pushURL + "&n=" + itoa(int64(i))}); code != http.StatusOK {
			t.Fatalf("registration %d: %d", i, code)
		}
	}
	if code, out := h.post(t, "/v1/register", "partner", map[string]string{"endpoint": h.pushURL + "&n=9"}); code != http.StatusConflict || out["error"] != "too_many" {
		t.Fatalf("fourth: %d %v", code, out)
	}
}

func TestUnregisterWithTheIdOrTheToken(t *testing.T) {
	h := newHarness(t)
	_, out := h.post(t, "/v1/register", "partner", map[string]string{"endpoint": h.pushURL})
	id := out["id"].(string)
	if code, _ := h.post(t, "/v1/unregister", "", map[string]string{"endpoint": h.pushURL, "id": "wrong"}); code != http.StatusUnauthorized {
		t.Fatalf("a wrong id without a token: %d", code)
	}
	if code, _ := h.post(t, "/v1/unregister", "", map[string]string{"endpoint": h.pushURL, "id": id}); code != http.StatusNoContent || h.store.Len() != 0 {
		t.Fatalf("unregister by id: %d, %d left", code, h.store.Len())
	}
	h.post(t, "/v1/register", "partner", map[string]string{"endpoint": h.pushURL})
	if code, _ := h.post(t, "/v1/unregister", "operator", map[string]string{"endpoint": h.pushURL}); code != http.StatusNoContent || h.store.Len() != 0 {
		t.Fatalf("unregister by token: %d", code)
	}
}

func TestTestPushNeedsTheIdAndIsRateLimited(t *testing.T) {
	h := newHarness(t)
	_, out := h.post(t, "/v1/register", "partner", map[string]string{"endpoint": h.pushURL})
	id := out["id"].(string)
	if code, _ := h.post(t, "/v1/test", "", map[string]string{"endpoint": h.pushURL, "id": "other"}); code != http.StatusNotFound {
		t.Fatalf("wrong id: %d", code)
	}
	if code, _ := h.post(t, "/v1/test", "", map[string]string{"endpoint": h.pushURL, "id": id}); code != http.StatusNoContent {
		t.Fatalf("test: %d", code)
	}
	if code, _ := h.post(t, "/v1/test", "", map[string]string{"endpoint": h.pushURL, "id": id}); code != http.StatusTooManyRequests {
		t.Fatalf("second test at once: %d", code)
	}
	got := h.endpoint.received()
	if len(got) != 1 || string(got[0]) != `{"tandem":"test","id":"`+id+`"}` {
		t.Fatalf("unexpected test payload %q", got)
	}
}

func TestInfoSaysNothingAboutTheServer(t *testing.T) {
	h := newHarness(t)
	resp, err := http.Get(h.server.URL + "/v1/info")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	text := string(data)
	if resp.StatusCode != http.StatusOK || !strings.Contains(text, `"service":"tandem-push"`) || strings.Contains(text, "127.0.0.1") {
		t.Fatalf("info: %d %s", resp.StatusCode, text)
	}
}

// --- Polling ---

func TestPollerDoesNotPollWithoutPhones(t *testing.T) {
	h := newHarness(t)
	before := h.tandoor.requestCount()
	if err := h.poller.PollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if h.tandoor.requestCount() != before {
		t.Fatal("polled Tandoor although no phone is registered")
	}
}

func TestPollerWakesPhonesWhenTheListChanged(t *testing.T) {
	h := newHarness(t)
	_, out := h.post(t, "/v1/register", "partner", map[string]string{"endpoint": h.pushURL})
	ctx := context.Background()
	h.tandoor.set(3, 0)
	if err := h.poller.PollOnce(ctx); err != nil { // baseline
		t.Fatal(err)
	}
	if err := h.poller.PollOnce(ctx); err != nil { // unchanged
		t.Fatal(err)
	}
	if n := len(h.endpoint.received()); n != 0 {
		t.Fatalf("woke %d times without a change", n)
	}
	h.tandoor.set(3, 1) // someone added or checked an item
	if err := h.poller.PollOnce(ctx); err != nil {
		t.Fatal(err)
	}
	h.tandoor.set(2, 0) // someone deleted an item (updated_after cannot see deletions)
	if err := h.poller.PollOnce(ctx); err != nil {
		t.Fatal(err)
	}
	got := h.endpoint.received()
	want := `{"tandem":"sync","id":"` + out["id"].(string) + `"}`
	if len(got) != 2 || string(got[0]) != want || string(got[1]) != want {
		t.Fatalf("expected two wake-ups %s, got %q", want, got)
	}
	header := h.endpoint.header(0)
	if header.Get("TTL") == "" || header.Get("Urgency") != "high" {
		t.Fatalf("missing web push headers: %v", header)
	}
	if h.poller.Status().Phones != 1 {
		t.Fatalf("status %+v", h.poller.Status())
	}
}

func TestGoneEndpointsAreDropped(t *testing.T) {
	h := newHarness(t)
	h.post(t, "/v1/register", "partner", map[string]string{"endpoint": h.pushURL})
	h.endpoint.setStatus(http.StatusGone)
	h.poller.WakeAll(context.Background(), h.store.Household(h.poller.household), KindSync)
	if h.store.Len() != 0 {
		t.Fatal("a 410 endpoint must be dropped")
	}
}

func TestOldRegistrationsAndOtherHouseholdsArePruned(t *testing.T) {
	h := newHarness(t)
	h.post(t, "/v1/register", "partner", map[string]string{"endpoint": h.pushURL})
	if _, err := h.store.Upsert("https://push.example.com/other", "", "", "s9-h9", h.now, 10); err != nil {
		t.Fatal(err)
	}
	h.now = h.now.Add(61 * 24 * time.Hour)
	_ = h.poller.PollOnce(context.Background())
	if h.store.Len() != 0 {
		t.Fatalf("expected everything pruned, %d left", h.store.Len())
	}
}

func TestStoreSurvivesARestart(t *testing.T) {
	dir := t.TempDir()
	s, _ := OpenStore(dir)
	r, err := s.Upsert("https://push.example.com/a", "", "", "s1-h2", time.Now(), 5)
	if err != nil {
		t.Fatal(err)
	}
	again, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	got, ok := again.Get("https://push.example.com/a")
	if !ok || got.ID != r.ID || got.Household != "s1-h2" {
		t.Fatalf("not restored: %+v", got)
	}
}

// --- Web Push encryption (RFC 8291) ---

func TestEncryptedWakeUpDecryptsWithThePhonesKeys(t *testing.T) {
	ua, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	auth := make([]byte, 16)
	_, _ = rand.Read(auth)
	p256dh := base64.RawURLEncoding.EncodeToString(ua.PublicKey().Bytes())
	authB64 := base64.RawURLEncoding.EncodeToString(auth)
	if !validWebPushKeys(p256dh, authB64) {
		t.Fatal("generated keys must be valid")
	}
	plain := []byte(`{"tandem":"sync","id":"abc"}`)
	body, err := encryptWebPush(plain, p256dh, authB64, rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	got, err := decryptWebPush(body, ua, auth)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatalf("decrypted %q", got)
	}
}

func TestEncryptedRegistrationSendsAes128gcm(t *testing.T) {
	h := newHarness(t)
	ua, _ := ecdh.P256().GenerateKey(rand.Reader)
	auth := make([]byte, 16)
	_, _ = rand.Read(auth)
	_, out := h.post(t, "/v1/register", "partner", map[string]string{
		"endpoint": h.pushURL,
		"p256dh":   base64.RawURLEncoding.EncodeToString(ua.PublicKey().Bytes()),
		"auth":     base64.RawURLEncoding.EncodeToString(auth),
	})
	h.poller.WakeAll(context.Background(), h.store.Household(h.poller.household), KindSync)
	if h.endpoint.header(0).Get("Content-Encoding") != "aes128gcm" {
		t.Fatal("expected an encrypted body")
	}
	got, err := decryptWebPush(h.endpoint.received()[0], ua, auth)
	if err != nil || string(got) != `{"tandem":"sync","id":"`+out["id"].(string)+`"}` {
		t.Fatalf("decrypted %q %v", got, err)
	}
}

// decryptWebPush is the phone's side (as Tink's WebPushHybridDecrypt does it), for the tests.
func decryptWebPush(body []byte, ua *ecdh.PrivateKey, auth []byte) ([]byte, error) {
	if len(body) < 86+16 {
		return nil, errors.New("too short")
	}
	salt := body[:16]
	rs := binary.BigEndian.Uint32(body[16:20])
	if rs != 4096 || body[20] != 65 {
		return nil, errors.New("bad header")
	}
	asPublicBytes := body[21:86]
	asPublic, err := ecdh.P256().NewPublicKey(asPublicBytes)
	if err != nil {
		return nil, err
	}
	shared, err := ua.ECDH(asPublic)
	if err != nil {
		return nil, err
	}
	keyInfo := append(append([]byte("WebPush: info\x00"), ua.PublicKey().Bytes()...), asPublicBytes...)
	ikm := hkdf(auth, shared, keyInfo, 32)
	cek := hkdf(salt, ikm, []byte("Content-Encoding: aes128gcm\x00"), 16)
	nonce := hkdf(salt, ikm, []byte("Content-Encoding: nonce\x00"), 12)
	block, _ := aes.NewCipher(cek)
	gcm, _ := cipher.NewGCM(block)
	plain, err := gcm.Open(nil, nonce, body[86:], nil)
	if err != nil {
		return nil, err
	}
	if len(plain) == 0 || plain[len(plain)-1] != 0x02 {
		return nil, errors.New("missing delimiter")
	}
	return plain[:len(plain)-1], nil
}

// TestHkdfMatchesRfc5869 checks the HKDF helper against RFC 5869's test case 1 (first 32 bytes).
func TestHkdfMatchesRfc5869(t *testing.T) {
	ikm := bytes.Repeat([]byte{0x0b}, 22)
	salt := []byte{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c}
	info := []byte{0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9}
	got := hkdf(salt, ikm, info, 32)
	want := "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
	if hexString(got) != want {
		t.Fatalf("hkdf %s", hexString(got))
	}
}

func hexString(b []byte) string {
	const digits = "0123456789abcdef"
	out := make([]byte, 0, len(b)*2)
	for _, c := range b {
		out = append(out, digits[c>>4], digits[c&0x0f])
	}
	return string(out)
}
