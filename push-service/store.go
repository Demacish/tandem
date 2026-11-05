package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Registration is everything the service keeps about one phone: the UnifiedPush endpoint, its
// optional Web Push keys (public key and auth secret, RFC 8291: only used to encrypt the wake-up),
// an opaque random id the app receives back, the household it belongs to and when it was last
// seen. No user, no token, no list content.
type Registration struct {
	ID        string    `json:"id"`
	Endpoint  string    `json:"endpoint"`
	P256DH    string    `json:"p256dh,omitempty"`
	Auth      string    `json:"auth,omitempty"`
	Household string    `json:"household"`
	LastSeen  time.Time `json:"last_seen"`
}

// ErrTooMany is returned when the household already has MaxEndpoints registrations.
var ErrTooMany = errors.New("too many registrations")

// Store keeps the registrations in memory and in one JSON file (written atomically).
type Store struct {
	mu   sync.Mutex
	path string
	regs map[string]Registration // by endpoint
}

type storeFile struct {
	Version       int            `json:"version"`
	Registrations []Registration `json:"registrations"`
}

// OpenStore loads dir/registrations.json (an empty store when the file does not exist yet).
func OpenStore(dir string) (*Store, error) {
	s := &Store{regs: map[string]Registration{}}
	if dir != "" {
		s.path = filepath.Join(dir, "registrations.json")
		data, err := os.ReadFile(s.path)
		switch {
		case errors.Is(err, os.ErrNotExist):
		case err != nil:
			return nil, err
		default:
			var file storeFile
			if err := json.Unmarshal(data, &file); err != nil {
				return nil, err
			}
			for _, r := range file.Registrations {
				s.regs[r.Endpoint] = r
			}
		}
	}
	return s, nil
}

// Upsert adds or refreshes the registration of endpoint and returns it (the id stays the same
// when the endpoint was registered before, so the app can keep it).
func (s *Store) Upsert(endpoint, p256dh, auth, household string, now time.Time, max int) (Registration, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, known := s.regs[endpoint]
	if !known {
		if s.countLocked(household) >= max {
			return Registration{}, ErrTooMany
		}
		r = Registration{ID: newID(), Endpoint: endpoint}
	}
	r.P256DH, r.Auth, r.Household, r.LastSeen = p256dh, auth, household, now.UTC()
	s.regs[endpoint] = r
	return r, s.saveLocked()
}

// Remove deletes endpoint's registration; it reports whether there was one.
func (s *Store) Remove(endpoint string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.regs[endpoint]; !ok {
		return false, nil
	}
	delete(s.regs, endpoint)
	return true, s.saveLocked()
}

// Get returns endpoint's registration.
func (s *Store) Get(endpoint string) (Registration, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.regs[endpoint]
	return r, ok
}

// Household returns the registrations of household, oldest endpoint first.
func (s *Store) Household(household string) []Registration {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []Registration
	for _, r := range s.regs {
		if r.Household == household {
			out = append(out, r)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Endpoint < out[j].Endpoint })
	return out
}

// Prune drops registrations of another household (the operator changed the token) and those not
// seen since before cutoff. It returns how many were dropped.
func (s *Store) Prune(household string, cutoff time.Time) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	dropped := 0
	for endpoint, r := range s.regs {
		if r.Household != household || r.LastSeen.Before(cutoff) {
			delete(s.regs, endpoint)
			dropped++
		}
	}
	if dropped == 0 {
		return 0, nil
	}
	return dropped, s.saveLocked()
}

// Len is the number of registrations.
func (s *Store) Len() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.regs)
}

func (s *Store) countLocked(household string) int {
	n := 0
	for _, r := range s.regs {
		if r.Household == household {
			n++
		}
	}
	return n
}

func (s *Store) saveLocked() error {
	if s.path == "" {
		return nil
	}
	file := storeFile{Version: 1, Registrations: make([]Registration, 0, len(s.regs))}
	for _, r := range s.regs {
		file.Registrations = append(file.Registrations, r)
	}
	sort.Slice(file.Registrations, func(i, j int) bool { return file.Registrations[i].Endpoint < file.Registrations[j].Endpoint })
	data, err := json.MarshalIndent(file, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(s.path), ".registrations-*.json")
	if err != nil {
		return err
	}
	name := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		_ = os.Remove(name)
		return err
	}
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		_ = os.Remove(name)
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(name)
		return err
	}
	return os.Rename(name, s.path)
}

// newID is 16 random bytes, hex: opaque, says nothing about the phone or the user.
func newID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
