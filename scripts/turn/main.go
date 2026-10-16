// DictaLab's native Windows TURN service. Protocol implementation: Pion TURN (MIT).
package main

import (
	"errors"
	"flag"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/pion/turn/v5"
)

const realm = "dictalab"

type udpRelay struct {
	turn.RelayAddressGeneratorPortRange
}

func (r *udpRelay) AllocatePacketConn(c turn.AllocateListenerConfig) (net.PacketConn, net.Addr, error) {
	if c.Network != "udp4" || (c.RequestedPort != 0 && (c.RequestedPort < 49160 || c.RequestedPort > 49959)) {
		return nil, nil, errors.New("only IPv4 UDP relay ports 49160-49959 are allowed")
	}
	return r.RelayAddressGeneratorPortRange.AllocatePacketConn(c)
}
func (*udpRelay) AllocateListener(turn.AllocateListenerConfig) (net.Listener, net.Addr, error) {
	return nil, nil, errors.New("TCP relay allocations are disabled; use UDP relay over TURN/TCP")
}
func (*udpRelay) AllocateConn(turn.AllocateConnConfig) (net.Conn, error) {
	return nil, errors.New("TCP peer connections are disabled")
}

func publicPeer(_ net.Addr, ip net.IP) bool {
	ip = ip.To4()
	if ip == nil || !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	// Exclude shared, benchmarking, reserved, and documentation networks too.
	for _, cidr := range []string{"0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4"} {
		_, network, _ := net.ParseCIDR(cidr)
		if network.Contains(ip) {
			return false
		}
	}
	return true
}

func authHandler(secret string) turn.AuthHandler {
	auth := turn.LongTermTURNRESTAuthHandler(secret, nil)
	return func(r *turn.RequestAttributes) (string, []byte, bool) {
		parts := strings.Split(r.Username, ":")
		if r.Realm != realm || len(parts) != 2 || len(r.Username) > 40 {
			return "", nil, false
		}
		expiry, err := strconv.ParseInt(parts[0], 10, 64)
		user, userErr := strconv.ParseInt(parts[1], 10, 64)
		now := time.Now().Unix()
		if err != nil || userErr != nil || user <= 0 || expiry <= now || expiry > now+3600 {
			return "", nil, false
		}
		return auth(r)
	}
}

func main() {
	ipFlag := flag.String("public-ip", "", "Public IPv4 assigned to this server")
	secretFile := flag.String("secret-file", "", "ACL-protected TURN shared-secret file")
	flag.Parse()
	ip := net.ParseIP(*ipFlag).To4()
	secret, err := os.ReadFile(*secretFile)
	if err != nil || len(strings.TrimSpace(string(secret))) < 32 || !publicPeer(nil, ip) {
		log.Fatal("A public IPv4 and protected secret file (at least 32 characters) are required")
	}
	udp, err := net.ListenPacket("udp4", "0.0.0.0:3478")
	if err != nil {
		log.Fatal(err)
	}
	tcp, err := net.Listen("tcp4", "0.0.0.0:3478")
	if err != nil {
		_ = udp.Close()
		log.Fatal(err)
	}
	relay := &udpRelay{turn.RelayAddressGeneratorPortRange{RelayAddress: ip, Address: "0.0.0.0", MinPort: 49160, MaxPort: 49959, MaxRetries: 100}}
	var lock sync.Mutex
	users := map[string]int{}
	total := 0
	server, err := turn.NewServer(turn.ServerConfig{
		Realm: realm, AuthHandler: authHandler(strings.TrimSpace(string(secret))),
		PacketConnConfigs: []turn.PacketConnConfig{{PacketConn: udp, RelayAddressGenerator: relay, PermissionHandler: publicPeer}},
		ListenerConfigs:   []turn.ListenerConfig{{Listener: tcp, RelayAddressGenerator: relay, PermissionHandler: publicPeer}},
		// ponytail: quota admission and creation are separate library callbacks; port range
		// is the hard 800-allocation ceiling even if concurrent requests race the soft quota.
		QuotaHandler: func(user, _ string, _ net.Addr) bool {
			lock.Lock()
			defer lock.Unlock()
			return total < 400 && users[user] < 4
		},
		EventHandler: turn.EventHandler{
			OnAllocationCreated: func(_, _ net.Addr, _, user, _ string, _ net.Addr, _ int) {
				lock.Lock()
				defer lock.Unlock()
				users[user]++
				total++
			},
			OnAllocationDeleted: func(_, _ net.Addr, _, user, _ string) {
				lock.Lock()
				defer lock.Unlock()
				if users[user] > 0 {
					users[user]--
					total--
					if users[user] == 0 {
						delete(users, user)
					}
				}
			},
		},
	})
	if err != nil {
		_ = udp.Close()
		_ = tcp.Close()
		log.Fatal(err)
	}
	log.Print("TURN ready: UDP/TCP 3478, UDP relay 49160-49959, temporary credentials required")
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	_ = server.Close()
}
