package proto

import (
	"encoding/json"
	"net"
	"testing"
)

func TestPhysicalMACsFiltersVirtualInterfaces(t *testing.T) {
	old := interfaces
	defer func() { interfaces = old }()
	interfaces = func() ([]net.Interface, error) {
		return []net.Interface{
			{Name: "lo", HardwareAddr: mustMAC("00:00:00:00:00:00"), Flags: net.FlagLoopback},
			{Name: "enp196s0f4u1u2", HardwareAddr: mustMAC("10:06:48:c0:1b:f9"), Flags: net.FlagUp | net.FlagBroadcast},
			{Name: "tailscale0", HardwareAddr: mustMAC("aa:bb:cc:dd:ee:ff"), Flags: net.FlagUp},
			{Name: "docker0", HardwareAddr: mustMAC("02:42:ac:11:00:01"), Flags: net.FlagUp},
			{Name: "veth1234", HardwareAddr: mustMAC("02:42:ac:11:00:02"), Flags: net.FlagUp},
			{Name: "wlan0", HardwareAddr: nil, Flags: net.FlagUp},
		}, nil
	}
	got := physicalMACs()
	if len(got) != 1 || got[0] != "10:06:48:c0:1b:f9" {
		t.Fatalf("physicalMACs() = %v", got)
	}
}

func TestNewIdentityCarriesWakeMACs(t *testing.T) {
	old := interfaces
	defer func() { interfaces = old }()
	interfaces = func() ([]net.Interface, error) {
		return []net.Interface{
			{Name: "eth0", HardwareAddr: mustMAC("10:06:48:c0:1b:f9"), Flags: net.FlagUp | net.FlagBroadcast},
		}, nil
	}
	id := NewIdentity("a738b2caef8f4cceb9a8caf3370a4024", "omarchy", 1716)
	raw, err := json.Marshal(id)
	if err != nil {
		t.Fatal(err)
	}
	var round map[string]any
	if err := json.Unmarshal(raw, &round); err != nil {
		t.Fatal(err)
	}
	macs, _ := round["fluxWakeMacs"].([]any)
	if len(macs) != 1 || macs[0] != "10:06:48:c0:1b:f9" {
		t.Fatalf("fluxWakeMacs = %v in %s", round["fluxWakeMacs"], raw)
	}
}

func TestIdentityOmitsWakeMACsWhenEmpty(t *testing.T) {
	old := interfaces
	defer func() { interfaces = old }()
	interfaces = func() ([]net.Interface, error) { return nil, nil }
	raw, err := json.Marshal(NewIdentity("a738b2caef8f4cceb9a8caf3370a4024", "omarchy", 0))
	if err != nil {
		t.Fatal(err)
	}
	if contains(string(raw), "fluxWakeMacs") {
		t.Fatalf("identity without MACs has fluxWakeMacs: %s", raw)
	}
}

func mustMAC(s string) net.HardwareAddr {
	m, err := net.ParseMAC(s)
	if err != nil {
		panic(err)
	}
	return m
}
