//go:build integration

package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

// TestPartnerItemWakesThePhone is plan §10 Fas 4 "Klart när" for push, against a throwaway
// Tandoor in GitHub Actions (push-service.yml): the service runs as the built container image; a
// phone of the household registers a fake UnifiedPush endpoint, the partner adds an item through
// Tandoor's API, and the endpoint must receive a wake-up within 60 s, without list content.
//
// Environment (set by the workflow, never real servers):
//
//	PUSH_SERVICE_URL  the running service, e.g. http://127.0.0.1:8090
//	TANDOOR_URL       the throwaway Tandoor
//	PHONE_TOKEN       a household member's token (the phone)
//	PARTNER_TOKEN     another household member's token (adds the item)
//	OUTSIDER_TOKEN    a member of another household (must be rejected)
func TestPartnerItemWakesThePhone(t *testing.T) {
	service := mustEnv(t, "PUSH_SERVICE_URL")
	tandoorURL := mustEnv(t, "TANDOOR_URL")
	phone := mustEnv(t, "PHONE_TOKEN")
	partner := mustEnv(t, "PARTNER_TOKEN")
	outsider := mustEnv(t, "OUTSIDER_TOKEN")

	received := make(chan []byte, 16)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var once sync.Once
	endpointServer := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		once.Do(func() { t.Logf("first wake-up after registration: %s", r.Header.Get("Content-Type")) })
		received <- body
		w.WriteHeader(http.StatusCreated)
	})}
	go func() { _ = endpointServer.Serve(listener) }()
	defer endpointServer.Close()
	endpoint := "http://" + listener.Addr().String() + "/upFakeTopic?up=1"

	// The service is up and says what it supports.
	waitForService(t, service)

	// Another household's member is turned away; nothing is stored for it.
	if code, out := postJSON(t, service+"/v1/register", outsider, map[string]string{"endpoint": endpoint + "&outsider=1"}); code != http.StatusForbidden {
		t.Fatalf("outsider: %d %v", code, out)
	}

	// With Web Push keys, like every real phone: the connector always supplies them.
	phoneKey, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	auth := make([]byte, 16)
	_, _ = rand.Read(auth)
	code, out := postJSON(t, service+"/v1/register", phone, map[string]string{
		"endpoint": endpoint,
		"p256dh":   base64.RawURLEncoding.EncodeToString(phoneKey.PublicKey().Bytes()),
		"auth":     base64.RawURLEncoding.EncodeToString(auth),
	})
	if code != http.StatusOK {
		t.Fatalf("register: %d %v", code, out)
	}
	id, _ := out["id"].(string)
	if id == "" {
		t.Fatalf("no id: %v", out)
	}

	// The test wake-up arrives at once.
	if code, out := postJSON(t, service+"/v1/test", "", map[string]string{"endpoint": endpoint, "id": id}); code != http.StatusNoContent {
		t.Fatalf("test push: %d %v", code, out)
	}
	expectWakeUp(t, received, phoneKey, auth, `{"tandem":"test","id":"`+id+`"}`, 10*time.Second)

	// Let the poller take its baseline (one poll interval of the CI service: 30 s), then the
	// partner adds an item.
	time.Sleep(35 * time.Second)
	drain(received)
	itemName := "kontraktsmjölk-push"
	added := time.Now()
	if code, out := postJSON(t, tandoorURL+"/api/shopping-list-entry/", partner, map[string]any{
		"food": map[string]string{"name": itemName}, "unit": nil, "amount": 1,
	}); code != http.StatusCreated {
		t.Fatalf("adding the item: %d %v", code, out)
	}
	expectWakeUp(t, received, phoneKey, auth, `{"tandem":"sync","id":"`+id+`"}`, 60*time.Second)
	t.Logf("wake-up %s after the partner's item", time.Since(added).Round(time.Second))

	// Unregistering with the id removes the phone: a test push is refused afterwards.
	if code, _ := postJSON(t, service+"/v1/unregister", "", map[string]string{"endpoint": endpoint, "id": id}); code != http.StatusNoContent {
		t.Fatalf("unregister: %d", code)
	}
	if code, _ := postJSON(t, service+"/v1/test", "", map[string]string{"endpoint": endpoint, "id": id}); code != http.StatusNotFound {
		t.Fatalf("test after unregister: %d", code)
	}
}

func mustEnv(t *testing.T, name string) string {
	t.Helper()
	v := os.Getenv(name)
	if v == "" {
		t.Skipf("%s is not set (the integration job sets it)", name)
	}
	return strings.TrimRight(v, "/")
}

func waitForService(t *testing.T, service string) {
	t.Helper()
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(service + "/v1/info")
		if err == nil {
			data, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK && strings.Contains(string(data), "tandem-push") {
				return
			}
		}
		time.Sleep(time.Second)
	}
	t.Fatal("the push service did not answer within 60 s")
}

func postJSON(t *testing.T, url, token string, body any) (int, map[string]any) {
	t.Helper()
	data, _ := json.Marshal(body)
	req, _ := http.NewRequest(http.MethodPost, url, bytes.NewReader(data))
	req.Header.Set("Content-Type", "application/json")
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

// expectWakeUp decrypts what arrives with the phone's keys and waits for exactly want, so a wake-up
// with list content in it cannot pass either.
func expectWakeUp(t *testing.T, received chan []byte, key *ecdh.PrivateKey, auth []byte, want string, within time.Duration) {
	t.Helper()
	timeout := time.After(within)
	for {
		select {
		case body := <-received:
			plain, err := decryptWebPush(body, key, auth)
			if err != nil {
				t.Fatalf("a wake-up the phone cannot decrypt (%d bytes): %v", len(body), err)
			}
			if string(plain) == want {
				return
			}
			t.Logf("ignoring another wake-up: %s", plain)
		case <-timeout:
			t.Fatalf("no wake-up %s within %s", want, within)
		}
	}
}

func drain(received chan []byte) {
	for {
		select {
		case <-received:
		default:
			return
		}
	}
}
