package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

// Errors of the Tandoor client. None of them carries a token or list content.
var (
	ErrUnauthorized = errors.New("tandoor rejected the token")
	ErrNoSpace      = errors.New("the user has no active space")
)

// RetryAfterError is a 429 (or 503) from Tandoor with the wait it asked for.
type RetryAfterError struct{ Wait time.Duration }

func (e *RetryAfterError) Error() string { return fmt.Sprintf("tandoor asked to wait %s", e.Wait) }

// StatusError is any other unexpected HTTP status, from Tandoor or a push endpoint.
type StatusError struct {
	From   string
	Status int
}

func (e *StatusError) Error() string { return fmt.Sprintf("%s answered HTTP %d", e.From, e.Status) }

// Membership is who a token belongs to, as far as the service needs it: the active space and the
// household (nil without one) from GET /api/user-space/all_personal/ (FACTS "d"), and whether the
// user is a guest (group "guest", read only).
type Membership struct {
	Space     int64
	Household *int64
	Guest     bool
}

// HouseholdKey is the opaque household id the service keeps ("s1-h2", "s1-h0" without one).
func (m Membership) HouseholdKey() string {
	var h int64
	if m.Household != nil {
		h = *m.Household
	}
	return fmt.Sprintf("s%d-h%d", m.Space, h)
}

// Snapshot is one look at the shopping list: how many entries the list has now, how many were
// changed since the last look and the server's timestamp for the next delta.
type Snapshot struct {
	Count     int
	Changed   int
	Timestamp string
}

// Tandoor is the small part of the Tandoor API the service uses (GET only).
type Tandoor struct {
	base   *url.URL
	client *http.Client
	agent  string
}

// noRedirects hands a 3xx back instead of following it.
func noRedirects(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }

// newTandoorClient follows no redirects, like the app: a token must not travel on to another
// address, and the only redirect Tandoor's API has is the login page. TANDOOR_URL is therefore the
// final address; a redirect shows up as "tandoor answered HTTP 30x" in the log.
func newTandoorClient() *http.Client {
	return &http.Client{Timeout: 30 * time.Second, CheckRedirect: noRedirects}
}

// NewTandoor returns a client for base (no trailing slash needed).
func NewTandoor(base *url.URL, client *http.Client, version string) *Tandoor {
	return &Tandoor{base: base, client: client, agent: "tandem-push/" + version}
}

type householdDTO struct {
	ID int64 `json:"id"`
}

type groupDTO struct {
	Name string `json:"name"`
}

type userSpaceDTO struct {
	Space     int64         `json:"space"`
	Active    bool          `json:"active"`
	Household *householdDTO `json:"household"`
	Groups    []groupDTO    `json:"groups"`
}

// Membership asks Tandoor who token belongs to. The token is only used for this one request.
func (t *Tandoor) Membership(ctx context.Context, token string) (Membership, error) {
	var spaces []userSpaceDTO
	if err := t.get(ctx, token, "/api/user-space/all_personal/", nil, &spaces); err != nil {
		return Membership{}, err
	}
	var chosen *userSpaceDTO
	for i := range spaces {
		if spaces[i].Active {
			chosen = &spaces[i]
			break
		}
	}
	if chosen == nil {
		return Membership{}, ErrNoSpace
	}
	m := Membership{Space: chosen.Space}
	if chosen.Household != nil {
		id := chosen.Household.ID
		m.Household = &id
	}
	for _, g := range chosen.Groups {
		if g.Name == "guest" {
			m.Guest = true
		}
	}
	return m, nil
}

type pageDTO struct {
	Count     int    `json:"count"`
	Timestamp string `json:"timestamp"`
}

// Snapshot reads the size of the shopping list and, when since is not empty, how many entries
// changed since then (`updated_after` is ">=" on updated_at and must be URL-encoded, FACTS
// "Fas 2 b"). Only counts are read: page_size=1, the entry itself is never looked at.
func (t *Tandoor) Snapshot(ctx context.Context, token, since string) (Snapshot, error) {
	var all pageDTO
	if err := t.get(ctx, token, "/api/shopping-list-entry/", url.Values{"page_size": {"1"}}, &all); err != nil {
		return Snapshot{}, err
	}
	snap := Snapshot{Count: all.Count, Timestamp: all.Timestamp}
	if since == "" {
		return snap, nil
	}
	var delta pageDTO
	query := url.Values{"page_size": {"1"}, "updated_after": {since}}
	if err := t.get(ctx, token, "/api/shopping-list-entry/", query, &delta); err != nil {
		return Snapshot{}, err
	}
	snap.Changed = delta.Count
	if delta.Timestamp != "" {
		// The delta's own time: nothing between the two requests is missed next time.
		snap.Timestamp = delta.Timestamp
	}
	return snap, nil
}

func (t *Tandoor) get(ctx context.Context, token, path string, query url.Values, into any) error {
	u := *t.base
	u.Path = t.base.Path + path
	if query != nil {
		u.RawQuery = query.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", t.agent)
	resp, err := t.client.Do(req)
	if err != nil {
		return fmt.Errorf("tandoor request failed: %w", redactURLError(err))
	}
	defer resp.Body.Close()
	body := io.LimitReader(resp.Body, 1<<20)
	switch {
	case resp.StatusCode == http.StatusOK:
		if err := json.NewDecoder(body).Decode(into); err != nil {
			return fmt.Errorf("tandoor answered unreadable JSON: %w", err)
		}
		return nil
	case resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
		_, _ = io.Copy(io.Discard, body)
		return ErrUnauthorized
	case resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode == http.StatusServiceUnavailable:
		_, _ = io.Copy(io.Discard, body)
		return &RetryAfterError{Wait: parseRetryAfter(resp.Header.Get("Retry-After"), time.Now())}
	default:
		_, _ = io.Copy(io.Discard, body)
		return &StatusError{From: "tandoor", Status: resp.StatusCode}
	}
}

// parseRetryAfter reads seconds or an HTTP date; 60 s when missing or unreadable, at most 1 h.
func parseRetryAfter(value string, now time.Time) time.Duration {
	wait := time.Minute
	if value != "" {
		if n, err := strconv.Atoi(value); err == nil && n >= 0 {
			wait = time.Duration(n) * time.Second
		} else if at, err := http.ParseTime(value); err == nil {
			wait = at.Sub(now)
		}
	}
	if wait < time.Second {
		wait = time.Second
	}
	if wait > time.Hour {
		wait = time.Hour
	}
	return wait
}

// redactURLError drops the URL (with its query) from a transport error, keeping the cause.
func redactURLError(err error) error {
	var urlErr *url.Error
	if errors.As(err, &urlErr) {
		return urlErr.Err
	}
	return err
}
