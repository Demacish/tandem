package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"
)

// APIVersion is the version of the app ↔ service protocol (docs/PUSH.md).
const APIVersion = 1

// API is the small HTTP API the app talks to:
//
//	GET  /healthz         liveness (the container's health check): only {healthy}
//	GET  /v1/info         what the service supports (no addresses, no counts)
//	POST /v1/register     Authorization: Bearer <the app's Tandoor token>, {endpoint, p256dh?, auth?},
//	                      or for a registered endpoint {endpoint, id, p256dh?, auth?} without the token
//	POST /v1/unregister   {endpoint, id} or the token instead of the id
//	POST /v1/test         {endpoint, id}: one test wake-up
//
// The app's Tandoor token is used once, to ask Tandoor which space and household it belongs to,
// and then forgotten: it is never stored and never logged.
//
// Limits: a request that proves a registration (its endpoint and id) has a bucket of its own, so
// nobody else can use it up. Everything else that gets as far as Tandoor shares a bucket per client
// address, and a request with neither an id nor a token is turned away without touching a bucket.
type API struct {
	cfg       Config
	version   string
	tandoor   *Tandoor
	store     *Store
	poller    *Poller
	pusher    *Pusher
	household string
	log       *slog.Logger
	now       func() time.Time
	limiter   *RateLimiter
	verifier  *RateLimiter

	testMu   sync.Mutex
	lastTest map[string]time.Time
}

// Routes returns the service's handler.
func (a *API) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", a.health)
	mux.HandleFunc("GET /v1/info", a.info)
	mux.HandleFunc("POST /v1/register", a.register)
	mux.HandleFunc("POST /v1/unregister", a.unregister)
	mux.HandleFunc("POST /v1/test", a.test)
	return mux
}

type infoResponse struct {
	Service     string `json:"service"`
	Version     string `json:"version"`
	API         int    `json:"api"`
	Test        bool   `json:"test"`
	Encryption  bool   `json:"encryption"`
	PollSeconds int    `json:"poll_seconds"`
}

func (a *API) info(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, infoResponse{
		Service:     "tandem-push",
		Version:     a.version,
		API:         APIVersion,
		Test:        a.cfg.TestPush,
		Encryption:  true,
		PollSeconds: int(a.cfg.Poll.Seconds()),
	})
}

type healthResponse struct {
	Healthy bool `json:"healthy"`
}

func (a *API) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, healthResponse{Healthy: a.poller.Status().Healthy})
}

type registerRequest struct {
	Endpoint string `json:"endpoint"`
	P256DH   string `json:"p256dh"`
	Auth     string `json:"auth"`
	ID       string `json:"id"`
}

type registerResponse struct {
	ID          string `json:"id"`
	PollSeconds int    `json:"poll_seconds"`
	Test        bool   `json:"test"`
}

func (a *API) register(w http.ResponseWriter, r *http.Request) {
	req, ok := a.readRequest(w, r)
	if !ok {
		return
	}
	if (req.P256DH == "") != (req.Auth == "") || (req.P256DH != "" && !validWebPushKeys(req.P256DH, req.Auth)) {
		writeError(w, http.StatusBadRequest, "invalid_keys")
		return
	}
	// A phone renewing its own registration proves it with the id, so its token stays on the phone
	// (it comes here on every app start). The membership was checked when the id was handed out.
	renewing, ok := a.admit(w, r, req)
	if !ok {
		return
	}
	reg, err := a.store.Upsert(req.Endpoint, req.P256DH, req.Auth, a.household, a.now(), a.cfg.MaxEndpoints)
	switch {
	case errors.Is(err, ErrTooMany):
		writeError(w, http.StatusConflict, "too_many")
		return
	case err != nil:
		a.log.Error("saving registrations failed", "error", err.Error())
		writeError(w, http.StatusInternalServerError, "storage")
		return
	}
	a.log.Info("phone registered", "endpoint_host", endpointHost(req.Endpoint), "encrypted", req.P256DH != "", "renewed", renewing)
	writeJSON(w, http.StatusOK, registerResponse{ID: reg.ID, PollSeconds: int(a.cfg.Poll.Seconds()), Test: a.cfg.TestPush})
}

func (a *API) unregister(w http.ResponseWriter, r *http.Request) {
	req, ok := a.readRequest(w, r)
	if !ok {
		return
	}
	// The registration's own id is enough (the phone may already be logged out); else the token.
	if _, ok := a.admit(w, r, req); !ok {
		return
	}
	removed, err := a.store.Remove(req.Endpoint)
	if err != nil {
		a.log.Error("saving registrations failed", "error", err.Error())
		writeError(w, http.StatusInternalServerError, "storage")
		return
	}
	if removed {
		a.log.Info("phone unregistered", "endpoint_host", endpointHost(req.Endpoint))
	}
	w.WriteHeader(http.StatusNoContent)
}

const testInterval = 10 * time.Second

func (a *API) test(w http.ResponseWriter, r *http.Request) {
	if !a.cfg.TestPush {
		writeError(w, http.StatusNotFound, "test_disabled")
		return
	}
	req, ok := a.readRequest(w, r)
	if !ok {
		return
	}
	reg, proven := a.registrationOf(req)
	if !proven {
		writeError(w, http.StatusNotFound, "not_registered")
		return
	}
	if !a.allow(w, registrationBucket(reg)) {
		return
	}
	a.testMu.Lock()
	last, seen := a.lastTest[reg.Endpoint]
	allowed := !seen || a.now().Sub(last) >= testInterval
	if allowed {
		a.lastTest[reg.Endpoint] = a.now()
	}
	a.testMu.Unlock()
	if !allowed {
		writeError(w, http.StatusTooManyRequests, "rate_limited")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	if err := a.pusher.Send(ctx, reg, KindTest); err != nil {
		if errors.Is(err, ErrEndpointGone) {
			_, _ = a.store.Remove(reg.Endpoint)
		}
		a.log.Warn("test push failed", "endpoint_host", endpointHost(reg.Endpoint), "error", err.Error())
		writeError(w, http.StatusBadGateway, "push_failed")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// readRequest decodes the JSON body (at most 8 KiB) and checks the endpoint.
func (a *API) readRequest(w http.ResponseWriter, r *http.Request) (registerRequest, bool) {
	var req registerRequest
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10))
	if err := dec.Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json")
		return req, false
	}
	req.Endpoint = strings.TrimSpace(req.Endpoint)
	if !validEndpoint(req.Endpoint, a.cfg.AllowHTTPEndpoints) {
		writeError(w, http.StatusBadRequest, "invalid_endpoint")
		return req, false
	}
	return req, true
}

// registrationOf returns the registration the request proves with its endpoint and id.
func (a *API) registrationOf(req registerRequest) (Registration, bool) {
	reg, known := a.store.Get(req.Endpoint)
	if !known || req.ID == "" || subtle.ConstantTimeCompare([]byte(reg.ID), []byte(req.ID)) != 1 {
		return Registration{}, false
	}
	return reg, true
}

// admit lets a request through on the registration's id, else on a member's token. proven says
// which; ok is false when the answer has been written.
func (a *API) admit(w http.ResponseWriter, r *http.Request, req registerRequest) (proven, ok bool) {
	if reg, proven := a.registrationOf(req); proven {
		return true, a.allow(w, registrationBucket(reg))
	}
	return false, a.verifyMember(w, r)
}

// verifyMember asks Tandoor whether the request's token belongs to the service's household.
func (a *API) verifyMember(w http.ResponseWriter, r *http.Request) bool {
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	token = strings.TrimSpace(token)
	if !ok || token == "" {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}
	if !a.allow(w, a.clientIP(r)) {
		return false
	}
	if !a.verifier.Allow("tandoor", a.now()) {
		writeError(w, http.StatusTooManyRequests, "rate_limited")
		return false
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	member, err := a.tandoor.Membership(ctx, token)
	switch {
	case errors.Is(err, ErrUnauthorized) || errors.Is(err, ErrNoSpace):
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	case err != nil:
		a.log.Warn("checking a phone's account failed", "error", err.Error())
		writeError(w, http.StatusBadGateway, "tandoor_unavailable")
		return false
	case member.Guest:
		writeError(w, http.StatusForbidden, "guest")
		return false
	case member.HouseholdKey() != a.household:
		writeError(w, http.StatusForbidden, "other_household")
		return false
	}
	return true
}

// allow takes one token from key's bucket, or answers 429.
func (a *API) allow(w http.ResponseWriter, key string) bool {
	if a.limiter.Allow(key, a.now()) {
		return true
	}
	w.Header().Set("Retry-After", "60")
	writeError(w, http.StatusTooManyRequests, "rate_limited")
	return false
}

func registrationBucket(reg Registration) string { return "registration:" + reg.ID }

// clientIP is the address a request is limited by: the peer, or behind a trusted proxy the client
// that proxy saw.
func (a *API) clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	peer, err := netip.ParseAddr(host)
	if err != nil {
		return host
	}
	return forwardedClient(peer, r.Header.Values("X-Forwarded-For"), a.cfg.TrustedProxies).String()
}

// forwardedClient walks X-Forwarded-For from the right, where each trusted proxy added the address
// it saw, to the first hop that is not a trusted proxy. Anything further left was written by the
// client and proves nothing, and from an untrusted peer the whole header does.
func forwardedClient(peer netip.Addr, forwarded []string, trusted []netip.Prefix) netip.Addr {
	client := peer.Unmap().WithZone("")
	if !isTrustedProxy(client, trusted) {
		return client
	}
	hops := strings.Split(strings.Join(forwarded, ","), ",")
	for i := len(hops) - 1; i >= 0; i-- {
		hop, err := netip.ParseAddr(strings.TrimSpace(hops[i]))
		if err != nil {
			break
		}
		client = hop.Unmap().WithZone("")
		if !isTrustedProxy(client, trusted) {
			break
		}
	}
	return client
}

func isTrustedProxy(ip netip.Addr, trusted []netip.Prefix) bool {
	for _, prefix := range trusted {
		if prefix.Contains(ip) {
			return true
		}
	}
	return false
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

// RateLimiter is a token bucket per key (rate per minute, burst), with idle keys forgotten.
type RateLimiter struct {
	mu      sync.Mutex
	perMin  float64
	burst   float64
	buckets map[string]*bucket
}

type bucket struct {
	tokens float64
	at     time.Time
}

// NewRateLimiter allows perMinute requests per key, burst at once.
func NewRateLimiter(perMinute, burst int) *RateLimiter {
	return &RateLimiter{perMin: float64(perMinute), burst: float64(burst), buckets: map[string]*bucket{}}
}

// Allow takes one token for key.
func (l *RateLimiter) Allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.buckets) > 10000 {
		for k, b := range l.buckets {
			if now.Sub(b.at) > 10*time.Minute {
				delete(l.buckets, k)
			}
		}
	}
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, at: now}
		l.buckets[key] = b
	}
	b.tokens += now.Sub(b.at).Minutes() * l.perMin
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	b.at = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}
