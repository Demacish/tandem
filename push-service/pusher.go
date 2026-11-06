package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"syscall"
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

// errInternalEndpoint is the dialer's refusal of an address on this machine.
var errInternalEndpoint = errors.New("push endpoint resolves to a loopback or link-local address")

// newPushClient is the client for wake-ups. Anyone holding a member's token can register an
// address, so it must not become a way into the service's own network: a redirect is never
// followed (an endpoint does not need one, and it could lead to http:// or anywhere else), and
// without ALLOW_HTTP_ENDPOINTS nothing is dialled on loopback or link-local addresses. The check
// is on the resolved address, so a name that later points there is covered too. Private LAN
// addresses stay allowed: a household's own ntfy may live there, and https needs a valid
// certificate anyway.
func newPushClient(allowInternal bool) *http.Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	if !allowInternal {
		dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second, Control: refuseInternalAddress}
		transport.DialContext = dialer.DialContext
	}
	return &http.Client{Timeout: 20 * time.Second, Transport: transport, CheckRedirect: noRedirects}
}

func refuseInternalAddress(_, address string, _ syscall.RawConn) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}
	if ip, err := netip.ParseAddr(host); err == nil && isInternal(ip) {
		return errInternalEndpoint
	}
	return nil
}

func isInternal(ip netip.Addr) bool {
	ip = ip.Unmap()
	return ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsUnspecified()
}

// internalHost is the same rule for what an endpoint spells out, refused before it is stored.
func internalHost(host string) bool {
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	if host == "localhost" || strings.HasSuffix(host, ".localhost") {
		return true
	}
	ip, err := netip.ParseAddr(host)
	return err == nil && isInternal(ip)
}

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
		return &StatusError{From: "push endpoint", Status: resp.StatusCode}
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
// ALLOW_HTTP_ENDPOINTS is for tests and a local distributor, so it also permits this machine.
func validEndpoint(endpoint string, allowHTTP bool) bool {
	if len(endpoint) > 2048 || strings.ContainsAny(endpoint, " \n\r\t") {
		return false
	}
	u, err := url.Parse(endpoint)
	if err != nil || u.Host == "" || u.User != nil || u.Fragment != "" {
		return false
	}
	if allowHTTP {
		return u.Scheme == "https" || u.Scheme == "http"
	}
	return u.Scheme == "https" && !internalHost(u.Hostname())
}
