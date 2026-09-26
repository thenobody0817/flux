package core

import "testing"

func TestRemoteAddr(t *testing.T) {
	cases := []struct {
		in   string
		host string
		port int
	}{
		{"", "", 0},
		{"  ", "", 0},
		{"omarchy.tailb898c2.ts.net", "omarchy.tailb898c2.ts.net", DefaultPort},
		{"omarchy.tailb898c2.ts.net:1750", "omarchy.tailb898c2.ts.net", 1750},
		{"100.100.254.55", "100.100.254.55", DefaultPort},
		{"100.100.254.55:1716", "100.100.254.55", 1716},
		{"[fd7a:115c:a1e0::1]:1716", "fd7a:115c:a1e0::1", 1716},
		{"host:notaport", "host", DefaultPort},
		{"host:99999", "host", DefaultPort},
	}
	for _, c := range cases {
		host, port := remoteAddr(c.in)
		if host != c.host || port != c.port {
			t.Errorf("remoteAddr(%q) = (%q, %d), want (%q, %d)", c.in, host, port, c.host, c.port)
		}
	}
}

func TestIsDirectOverlayIsNotDirect(t *testing.T) {
	// A Tailscale address is carried by tailscale0, so it must not count as
	// directly attached. Loopback and unparsable input are not direct too.
	for _, ip := range []string{"100.100.254.55", "127.0.0.1", "::1", "", "not-an-ip"} {
		if isDirect(ip) {
			t.Errorf("isDirect(%q) = true, want false", ip)
		}
	}
}
