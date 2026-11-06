// Command push-service is Tandem's optional push companion (plan §4.3, B11): it polls one
// household's Tandoor shopping list and wakes the household's phones through UnifiedPush when
// the list changed. The wake-up carries no list content; the phone syncs and shows its own
// notification. See README.md and docs/PUSH.md.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

// version is set at build time (-ldflags "-X main.version=...").
var version = "dev"

func main() {
	healthcheck := flag.Bool("healthcheck", false, "check the running service's /healthz and exit (for the container)")
	flag.Parse()
	if *healthcheck {
		os.Exit(runHealthcheck(os.Getenv("LISTEN_ADDR")))
	}
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "push-service:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := LoadConfig(os.Getenv)
	if err != nil {
		return err
	}
	log := newLogger(cfg.LogLevel)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	store, err := OpenStore(cfg.DataDir)
	if err != nil {
		return fmt.Errorf("reading registrations: %w", err)
	}
	tandoor := NewTandoor(cfg.TandoorURL, newTandoorClient(), version)
	pusher := &Pusher{client: newPushClient(cfg.AllowHTTPEndpoints), log: log}

	log.Info("starting", "version", version, "poll_seconds", int(cfg.Poll.Seconds()), "registrations", store.Len())
	household, err := operatorHousehold(ctx, tandoor, cfg.TandoorToken, log)
	if err != nil {
		return err
	}
	log.Info("serving one household", "household", household)

	poller := &Poller{cfg: cfg, tandoor: tandoor, store: store, pusher: pusher, log: log, now: time.Now, household: household}
	api := &API{
		cfg: cfg, version: version, tandoor: tandoor, store: store, poller: poller, pusher: pusher,
		household: household, log: log, now: time.Now,
		limiter:  NewRateLimiter(20, 10),
		verifier: NewRateLimiter(60, 20),
		lastTest: map[string]time.Time{},
	}
	server := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           api.Routes(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	go poller.Run(ctx)
	errs := make(chan error, 1)
	go func() { errs <- server.ListenAndServe() }()
	log.Info("listening", "addr", cfg.ListenAddr)

	select {
	case <-ctx.Done():
	case err := <-errs:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	log.Info("stopping")
	return server.Shutdown(shutdown)
}

// operatorHousehold asks Tandoor which household the operator's token belongs to, retrying with
// backoff while Tandoor is not reachable yet (e.g. both containers start together).
func operatorHousehold(ctx context.Context, tandoor *Tandoor, token string, log *slog.Logger) (string, error) {
	backoff := Backoff{Interval: 5 * time.Second}
	for {
		member, err := tandoor.Membership(ctx, token)
		if err == nil {
			return member.HouseholdKey(), nil
		}
		if errors.Is(err, ErrNoSpace) {
			return "", errors.New("the TANDOOR_TOKEN user has no active space")
		}
		wait := backoff.Next(err)
		if errors.Is(err, ErrUnauthorized) {
			// The API stays closed until this works, and phones only see an unreachable service.
			log.Error("Tandoor rejected TANDOOR_TOKEN: fix the token and restart the service", "retry_in", wait.String())
		} else {
			log.Warn("cannot reach Tandoor yet", "error", err.Error(), "retry_in", wait.String())
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(wait):
		}
	}
}

func newLogger(level string) *slog.Logger {
	var l slog.Level
	_ = l.UnmarshalText([]byte(level))
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: l}))
}

// runHealthcheck GETs /healthz on the local port of listenAddr: 0 when it answers 200.
func runHealthcheck(listenAddr string) int {
	if listenAddr == "" {
		listenAddr = defaultListenAddr
	}
	_, port, err := net.SplitHostPort(listenAddr)
	if err != nil {
		return 1
	}
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://127.0.0.1:" + port + "/healthz")
	if err != nil {
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
