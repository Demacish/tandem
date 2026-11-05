package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Wake-up kinds. The payload is only {"tandem": kind, "id": <the registration's opaque id>}:
// never list content (plan §4.3, B11). The phone syncs and shows its own notification.
const (
	KindSync = "sync"
	KindTest = "test"
)

// ErrEndpointGone means the distributor forgot the endpoint (404/410): the registration is dropped.
var ErrEndpointGone = errors.New("push endpoint is gone")

// Pusher POSTs wake-ups to UnifiedPush endpoints.
type Pusher struct {
	client *http.Client
	log    *slog.Logger
}

type wakeUp struct {
	Tandem string `json:"tandem"`
	ID     string `json:"id"`
}

// Send delivers one wake-up of kind to r.
func (p *Pusher) Send(ctx context.Context, r Registration, kind string) error {
	payload, _ := json.Marshal(wakeUp{Tandem: kind, ID: r.ID})
	body := payload
	encrypted := false
	if r.P256DH != "" && r.Auth != "" {
		sealed, err := encryptWebPush(payload, r.P256DH, r.Auth, randomReader)
		if err != nil {
			return fmt.Errorf("encrypting the wake-up: %w", err)
		}
		body, encrypted = sealed, true
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.Endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	// Web Push headers (RFC 8030): keep it for 5 minutes, deliver at once.
	req.Header.Set("TTL", "300")
	req.Header.Set("Urgency", "high")
	if encrypted {
		req.Header.Set("Content-Encoding", "aes128gcm")
		req.Header.Set("Content-Type", "application/octet-stream")
	} else {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("push failed: %w", redactURLError(err))
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	switch {
	case resp.StatusCode >= 200 && resp.StatusCode < 300:
		return nil
	case resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone:
		return ErrEndpointGone
	default:
		return &StatusError{Status: resp.StatusCode}
	}
}

// endpointHost is what the logs say about an endpoint: its host only (the path is a secret topic).
func endpointHost(endpoint string) string {
	u, err := url.Parse(endpoint)
	if err != nil {
		return "invalid"
	}
	return u.Host
}

// validEndpoint accepts https URLs (http only when allowed), without user info, at most 2 KiB.
func validEndpoint(endpoint string, allowHTTP bool) bool {
	if len(endpoint) > 2048 || strings.ContainsAny(endpoint, " \n\r\t") {
		return false
	}
	u, err := url.Parse(endpoint)
	if err != nil || u.Host == "" || u.User != nil || u.Fragment != "" {
		return false
	}
	return u.Scheme == "https" || (allowHTTP && u.Scheme == "http")
}
