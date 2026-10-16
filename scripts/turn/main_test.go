package main

import (
	"bytes"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/pion/turn/v5"
)

func TestPublicPeerAndTemporaryAuth(t *testing.T) {
	for _, address := range []string{"127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "100.64.1.1", "198.18.0.1", "192.0.2.1", "224.0.0.1", "240.0.0.1", "0.0.0.0", "::1", "fd00::1"} {
		if publicPeer(nil, net.ParseIP(address)) {
			t.Fatalf("Internal/reserved peer allowed: %s", address)
		}
	}
	if !publicPeer(nil, net.ParseIP("160.191.244.27")) {
		t.Fatal("Public relay peer blocked")
	}
	secret := "test-only-secret-with-at-least-32-characters"
	username, password, err := turn.GenerateLongTermTURNRESTCredentials(secret, "42", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	handler := authHandler(secret)
	id, key, ok := handler(&turn.RequestAttributes{Username: username, Realm: realm})
	if !ok || id != "42" || !bytes.Equal(key, turn.GenerateAuthKey(username, realm, password)) {
		t.Fatal("Java-compatible REST credential rejected")
	}
	timestamp, _, _ := strings.Cut(username, ":")
	for _, invalid := range []string{"", "abc:42", strconv.FormatInt(time.Now().Unix()-1, 10) + ":42", strconv.FormatInt(time.Now().Unix()+7200, 10) + ":42", timestamp + ":0", username + ":extra"} {
		if _, _, ok := handler(&turn.RequestAttributes{Username: invalid, Realm: realm}); ok {
			t.Fatalf("Invalid credential accepted: %s", invalid)
		}
	}
	if _, _, ok := handler(&turn.RequestAttributes{Username: username, Realm: "other"}); ok {
		t.Fatal("Wrong realm accepted")
	}
}

func TestRelayCannotBypassPortRangeOrUseTCP(t *testing.T) {
	r := &udpRelay{}
	for _, c := range []turn.AllocateListenerConfig{{Network: "udp4", RequestedPort: 22}, {Network: "udp6"}} {
		if _, _, err := r.AllocatePacketConn(c); err == nil {
			t.Fatal("Forbidden relay request accepted")
		}
	}
	if _, _, err := r.AllocateListener(turn.AllocateListenerConfig{}); err == nil {
		t.Fatal("TCP relay accepted")
	}
	if _, err := r.AllocateConn(turn.AllocateConnConfig{}); err == nil {
		t.Fatal("TCP peer accepted")
	}
}
