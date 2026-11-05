package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// APIVersion is the version of the app ↔ service protocol (docs/PUSH.md).
const APIVersion = 1

// API is the small HTTP API the app talks to:
//
//	GET  /healthz         liveness (the container's health check)
//	GET  /v1/info         what the service supports (no addresses, no counts)
//	POST /v1/register     Authorization: Bearer <the app's Tandoor token>, {endpoint, p256dh?, auth?}
//	POST /v1/unregister   {endpoint, id} or the token instead of the id
//	POST /v1/test         {endpoint, id}: one test wake-up
//
// The app's Tandoor token is used once, to ask Tandoor which space and household it belongs to,
// and then forgotten: it is never stored and never logged.
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
	mux.HandleFunc("POST /v1/register", a.limited(a.register))
	mux.HandleFunc("POST /v1/unregister", a.limited(a.unregister))
	mux.HandleFunc("POST /v1/test", a.limited(a.test))
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

func (a *API) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, a.poller.Status())
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
	if !a.verifyMember(w, r) {
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
	a.log.Info("phone registered", "endpoint_host", endpointHost(req.Endpoint), "encrypted", req.P256DH != "")
	writeJSON(w, http.StatusOK, registerResponse{ID: reg.ID, PollSeconds: int(a.cfg.Poll.Seconds()), Test: a.cfg.TestPush})
}

func (a *API) unregister(w http.ResponseWriter, r *http.Request) {
	req, ok := a.readRequest(w, r)
	if !ok {
		return
	}
	existing, known := a.store.Get(req.Endpoint)
	// The registration's own id is enough (the phone may already be logged out); else the token.
	if !(known && req.ID != "" && existing.ID == req.ID) {
		if !a.verifyMember(w, r) {
			return
		}
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
	reg, known := a.store.Get(req.Endpoint)
	if !known || req.ID == "" || reg.ID != req.ID {
		writeError(w, http.StatusNotFound, "not_registered")
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

// verifyMember asks Tandoor whether the request's token belongs to the service's household.
func (a *API) verifyMember(w http.ResponseWriter, r *http.Request) bool {
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	token = strings.TrimSpace(token)
	if !ok || token == "" {
		writeError(w, http.StatusUnauthorized, "unauthorized")
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

// limited applies the per-client rate limit.
func (a *API) limited(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.limiter.Allow(clientIP(r), a.now()) {
			w.Header().Set("Retry-After", "60")
			writeError(w, http.StatusTooManyRequests, "rate_limited")
			return
		}
		next(w, r)
	}
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
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
