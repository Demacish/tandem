package main

import (
	"errors"
	"fmt"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config is read from environment variables only (docs/PUSH.md). There are no defaults that
// point at any real host: TANDOOR_URL and the token must always be given by the operator.
type Config struct {
	// TandoorURL is the base URL of the Tandoor server, e.g. https://recept.example.com.
	TandoorURL *url.URL
	// TandoorToken is the operator's own Tandoor API token (read scope is enough).
	TandoorToken string
	// Poll is how often the shopping list is checked (30–600 s, plan §4.3 A2: 30–60 s).
	Poll time.Duration
	// ListenAddr is where the small HTTP API listens, e.g. ":8080".
	ListenAddr string
	// DataDir holds registrations.json (a volume in the container).
	DataDir string
	// AllowHTTPEndpoints permits http:// push endpoints and endpoints on this machine (tests, a
	// LAN-only ntfy). Off by default.
	AllowHTTPEndpoints bool
	// TrustedProxies are the reverse proxies whose X-Forwarded-For is believed. Empty by default:
	// the header is ignored and every client behind a proxy shares that proxy's rate limit.
	TrustedProxies []netip.Prefix
	// MaxEndpoints caps the registrations of the household.
	MaxEndpoints int
	// RegistrationTTL drops registrations not seen (re-registered) for this long.
	RegistrationTTL time.Duration
	// TestPush enables POST /v1/test ("Send test notification" in the app).
	TestPush bool
	// LogLevel is debug, info, warn or error.
	LogLevel string
}

const (
	defaultPoll         = 45 * time.Second
	minPoll             = 30 * time.Second
	maxPoll             = 600 * time.Second
	defaultListenAddr   = ":8080"
	defaultDataDir      = "/data"
	defaultMaxEndpoints = 20
	defaultTTLDays      = 60
)

// LoadConfig reads the configuration from getenv (os.Getenv in production, a map in tests).
func LoadConfig(getenv func(string) string) (Config, error) {
	var problems []string
	cfg := Config{
		Poll:            defaultPoll,
		ListenAddr:      defaultListenAddr,
		DataDir:         defaultDataDir,
		MaxEndpoints:    defaultMaxEndpoints,
		RegistrationTTL: defaultTTLDays * 24 * time.Hour,
		TestPush:        true,
		LogLevel:        "info",
	}

	raw := strings.TrimSpace(getenv("TANDOOR_URL"))
	if raw == "" {
		problems = append(problems, "TANDOOR_URL is required")
	} else {
		u, err := url.Parse(strings.TrimRight(raw, "/"))
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			problems = append(problems, "TANDOOR_URL must be an http(s) URL")
		} else {
			cfg.TandoorURL = u
		}
	}

	token := strings.TrimSpace(getenv("TANDOOR_TOKEN"))
	if file := strings.TrimSpace(getenv("TANDOOR_TOKEN_FILE")); token == "" && file != "" {
		data, err := os.ReadFile(file)
		if err != nil {
			problems = append(problems, "TANDOOR_TOKEN_FILE cannot be read")
		} else {
			token = strings.TrimSpace(string(data))
		}
	}
	if token == "" {
		problems = append(problems, "TANDOOR_TOKEN (or TANDOOR_TOKEN_FILE) is required")
	}
	cfg.TandoorToken = token

	if v := strings.TrimSpace(getenv("POLL_SECONDS")); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || time.Duration(n)*time.Second < minPoll || time.Duration(n)*time.Second > maxPoll {
			problems = append(problems, fmt.Sprintf("POLL_SECONDS must be %d–%d", int(minPoll.Seconds()), int(maxPoll.Seconds())))
		} else {
			cfg.Poll = time.Duration(n) * time.Second
		}
	}
	if v := strings.TrimSpace(getenv("LISTEN_ADDR")); v != "" {
		cfg.ListenAddr = v
	}
	if v := strings.TrimSpace(getenv("DATA_DIR")); v != "" {
		cfg.DataDir = v
	}
	if v := strings.TrimSpace(getenv("ALLOW_HTTP_ENDPOINTS")); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			problems = append(problems, "ALLOW_HTTP_ENDPOINTS must be true or false")
		}
		cfg.AllowHTTPEndpoints = b
	}
	if v := strings.TrimSpace(getenv("TRUSTED_PROXIES")); v != "" {
		proxies, err := parseTrustedProxies(v)
		if err != nil {
			problems = append(problems, "TRUSTED_PROXIES must be comma-separated IP addresses or CIDR ranges")
		}
		cfg.TrustedProxies = proxies
	}
	if v := strings.TrimSpace(getenv("TEST_PUSH")); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			problems = append(problems, "TEST_PUSH must be true or false")
		} else {
			cfg.TestPush = b
		}
	}
	if v := strings.TrimSpace(getenv("MAX_ENDPOINTS")); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 1000 {
			problems = append(problems, "MAX_ENDPOINTS must be 1–1000")
		} else {
			cfg.MaxEndpoints = n
		}
	}
	if v := strings.TrimSpace(getenv("REGISTRATION_TTL_DAYS")); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 3650 {
			problems = append(problems, "REGISTRATION_TTL_DAYS must be 1–3650")
		} else {
			cfg.RegistrationTTL = time.Duration(n) * 24 * time.Hour
		}
	}
	if v := strings.ToLower(strings.TrimSpace(getenv("LOG_LEVEL"))); v != "" {
		switch v {
		case "debug", "info", "warn", "error":
			cfg.LogLevel = v
		default:
			problems = append(problems, "LOG_LEVEL must be debug, info, warn or error")
		}
	}

	if len(problems) > 0 {
		return cfg, errors.New(strings.Join(problems, "; "))
	}
	return cfg, nil
}

// parseTrustedProxies reads "10.0.0.1, 172.16.0.0/12": a single address is its own range.
func parseTrustedProxies(value string) ([]netip.Prefix, error) {
	var proxies []netip.Prefix
	for _, part := range strings.Split(value, ",") {
		part = strings.TrimSpace(part)
		if prefix, err := netip.ParsePrefix(part); err == nil {
			proxies = append(proxies, prefix.Masked())
			continue
		}
		addr, err := netip.ParseAddr(part)
		if err != nil {
			return nil, err
		}
		addr = addr.Unmap().WithZone("")
		proxies = append(proxies, netip.PrefixFrom(addr, addr.BitLen()))
	}
	return proxies, nil
}
