package main

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"
)

// Backoff is the poller's circuit breaker (plan §5.4): after a failure the next poll waits
// interval × 2^failures (at most maxBackoff, at least what a 429 asked for); a rejected operator
// token waits maxBackoff straight away. One success closes it again.
type Backoff struct {
	Interval time.Duration
	failures int
}

const maxBackoff = 15 * time.Minute

// Next is the wait before the next poll after a result (err == nil: success).
func (b *Backoff) Next(err error) time.Duration {
	if err == nil {
		b.failures = 0
		return b.Interval
	}
	b.failures++
	wait := b.Interval
	for i := 0; i < b.failures && wait < maxBackoff; i++ {
		wait *= 2
	}
	if errors.Is(err, ErrUnauthorized) {
		wait = maxBackoff
	}
	var retry *RetryAfterError
	if errors.As(err, &retry) && retry.Wait > wait {
		wait = retry.Wait
	}
	if wait > maxBackoff {
		wait = maxBackoff
	}
	return wait
}

// Open reports whether the breaker is open (at least one failure in a row).
func (b *Backoff) Open() bool { return b.failures > 0 }

// Poller checks the household's shopping list once per interval and wakes the registered phones
// when something changed. It never polls while no phone is registered.
type Poller struct {
	cfg       Config
	tandoor   *Tandoor
	store     *Store
	pusher    *Pusher
	log       *slog.Logger
	now       func() time.Time
	household string

	mu    sync.Mutex
	since string
	count int
	// sentAt is when the last wake-up went out (status only).
	sentAt time.Time
	// healthy is false while the breaker is open.
	healthy bool
}

// PollerStatus is what /healthz reports (no ids, no endpoints).
type PollerStatus struct {
	Healthy  bool      `json:"healthy"`
	LastWake time.Time `json:"last_wake,omitzero"`
	Phones   int       `json:"phones"`
}

// Status is the poller's state for the health check.
func (p *Poller) Status() PollerStatus {
	p.mu.Lock()
	defer p.mu.Unlock()
	return PollerStatus{Healthy: p.healthy, LastWake: p.sentAt, Phones: len(p.store.Household(p.household))}
}

// Run polls until ctx ends.
func (p *Poller) Run(ctx context.Context) {
	backoff := Backoff{Interval: p.cfg.Poll}
	p.mu.Lock()
	p.healthy = true
	p.mu.Unlock()
	timer := time.NewTimer(0)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		}
		err := p.PollOnce(ctx)
		wasOpen := backoff.Open()
		wait := backoff.Next(err)
		p.mu.Lock()
		p.healthy = err == nil
		p.mu.Unlock()
		switch {
		case err != nil && !wasOpen:
			p.log.Warn("poll failed, backing off", "error", err.Error(), "retry_in", wait.String())
		case err != nil:
			p.log.Debug("poll failed again", "error", err.Error(), "retry_in", wait.String())
		case wasOpen:
			p.log.Info("poll works again")
		}
		timer.Reset(wait)
	}
}

// PollOnce does one check and, when the list changed, wakes every phone of the household.
func (p *Poller) PollOnce(ctx context.Context) error {
	if dropped, err := p.store.Prune(p.household, p.now().Add(-p.cfg.RegistrationTTL)); err != nil {
		p.log.Error("saving registrations failed", "error", err.Error())
	} else if dropped > 0 {
		p.log.Info("dropped old registrations", "count", dropped)
	}
	phones := p.store.Household(p.household)
	p.mu.Lock()
	since, count := p.since, p.count
	p.mu.Unlock()
	if len(phones) == 0 {
		// Nobody to wake: do not poll, and start from a fresh baseline when a phone registers.
		p.mu.Lock()
		p.since, p.count = "", 0
		p.mu.Unlock()
		return nil
	}

	pollCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	snap, err := p.tandoor.Snapshot(pollCtx, p.cfg.TandoorToken, since)
	if err != nil {
		return err
	}
	p.mu.Lock()
	p.since, p.count = snap.Timestamp, snap.Count
	p.mu.Unlock()
	if since == "" {
		return nil // the first look is the baseline
	}
	if snap.Changed == 0 && snap.Count == count {
		return nil
	}
	p.log.Info("shopping list changed, waking phones", "phones", len(phones))
	p.WakeAll(ctx, phones, KindSync)
	return nil
}

// WakeAll sends kind to every phone; endpoints the distributor forgot are dropped.
func (p *Poller) WakeAll(ctx context.Context, phones []Registration, kind string) {
	for _, r := range phones {
		err := p.pusher.Send(ctx, r, kind)
		switch {
		case err == nil:
			p.mu.Lock()
			p.sentAt = p.now().UTC()
			p.mu.Unlock()
		case errors.Is(err, ErrEndpointGone):
			if _, rmErr := p.store.Remove(r.Endpoint); rmErr != nil {
				p.log.Error("saving registrations failed", "error", rmErr.Error())
			}
			p.log.Info("push endpoint gone, registration dropped", "endpoint_host", endpointHost(r.Endpoint))
		default:
			p.log.Warn("push failed", "endpoint_host", endpointHost(r.Endpoint), "error", err.Error())
		}
	}
}
