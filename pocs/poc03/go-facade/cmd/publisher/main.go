// Nó publicador/bootstrap de HOST da PoC poc-03 (E2/E3/E4) — go-libp2p.
//
// Espelha o publicador rust (src/bin/publisher.rs): escuta TCP+QUIC, Kademlia server,
// anuncia provider do obraId e serve o capítulo de teste pelo mesmo stream protocol que o
// facade go client lê (length-prefixed até EOF). Mesma seed de conteúdo → mesma pubkey do
// rust, então o app verifica com a mesma chave. A verificação é no app (D7).
//
// uso: publisher <porta> <obraId> [bootstrap_multiaddr]
package main

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/ipfs/go-cid"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/p2p/security/noise"
	libp2pquic "github.com/libp2p/go-libp2p/p2p/transport/quic"
	libp2ptcp "github.com/libp2p/go-libp2p/p2p/transport/tcp"
	mh "github.com/multiformats/go-multihash"
)

const blockProtocol protocol.ID = "/opentoons/blocks/1.0.0"

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "uso: publisher <porta> <obraId> [bootstrap]")
		os.Exit(1)
	}
	port := os.Args[1]
	obraID := os.Args[2]
	ctx := context.Background()

	// identidade libp2p determinística pela porta
	var seed [32]byte
	copy(seed[:], "poc03-go-publisher-port-"+port)
	priv, _, err := crypto.GenerateEd25519Key(newSeedReader(seed))
	must(err)

	// chave de CONTEÚDO: mesma seed do publicador rust (0x42*32) → mesma pubkey
	var contentSeed [32]byte
	for i := range contentSeed {
		contentSeed[i] = 0x42
	}
	contentPriv := ed25519.NewKeyFromSeed(contentSeed[:])
	contentPub := contentPriv.Public().(ed25519.PublicKey)
	chapter := buildTestChapter(obraID, contentPriv, contentPub)

	h, err := libp2p.New(
		libp2p.Identity(priv),
		libp2p.ListenAddrStrings(
			"/ip4/0.0.0.0/tcp/"+port,
			"/ip4/0.0.0.0/udp/"+port+"/quic-v1",
		),
		libp2p.Security(noise.ID, noise.New),
		libp2p.Transport(libp2ptcp.NewTCPTransport),
		libp2p.Transport(libp2pquic.NewTransport),
	)
	must(err)

	// handler do stream de blocos: lê cids até "\n\n", devolve o capítulo length-prefixed, fecha
	h.SetStreamHandler(blockProtocol, func(s network.Stream) {
		defer s.Close()
		r := bufio.NewReader(s)
		var req []string
		for {
			line, err := r.ReadString('\n')
			line = strings.TrimSpace(line)
			if line == "" {
				break
			}
			req = append(req, line)
			if err != nil {
				break
			}
		}
		fmt.Fprintf(os.Stderr, "REQUEST de %s: %v\n", s.Conn().RemotePeer(), req)
		var lenBuf [4]byte
		for _, block := range chapter {
			binary.BigEndian.PutUint32(lenBuf[:], uint32(len(block)))
			if _, err := s.Write(lenBuf[:]); err != nil {
				return
			}
			if _, err := s.Write(block); err != nil {
				return
			}
		}
		_ = s.CloseWrite()
	})

	kdht, err := dht.New(ctx, h, dht.Mode(dht.ModeServer))
	must(err)
	must(kdht.Bootstrap(ctx))

	if len(os.Args) > 3 {
		connectBootstrap(ctx, h, os.Args[3])
	}

	// anuncia-se provider do obraId (descoberta fria do E3)
	c := obraCID(obraID)
	go func() {
		for {
			if err := kdht.Provide(ctx, c, true); err != nil {
				fmt.Fprintf(os.Stderr, "provide err: %v\n", err)
			}
			time.Sleep(1 * time.Minute)
		}
	}()

	fmt.Fprintln(os.Stderr, "=== PUBLISHER GO ===")
	fmt.Fprintf(os.Stderr, "peerId=%s\n", h.ID())
	fmt.Fprintf(os.Stderr, "obraId=%s (cid=%s)\n", obraID, c)
	fmt.Fprintf(os.Stderr, "contentPubKeyHex=%s\n", hex.EncodeToString(contentPub))
	for _, a := range h.Addrs() {
		fmt.Fprintf(os.Stderr, "LISTEN %s/p2p/%s\n", a, h.ID())
	}
	select {} // roda até morrer
}

func obraCID(obraID string) cid.Cid {
	if c, err := cid.Decode(obraID); err == nil {
		return c
	}
	hsh, _ := mh.Sum([]byte(obraID), mh.SHA2_256, -1)
	return cid.NewCidV1(cid.Raw, hsh)
}

func buildTestChapter(obraID string, contentPriv ed25519.PrivateKey, contentPub []byte) [][]byte {
	contents := make([][]byte, 3)
	cids := make([]string, 3)
	for i := 0; i < 3; i++ {
		contents[i] = []byte(strings.Repeat(fmt.Sprintf("pagina-%d-", i+1), 200))
		sum := sha256.Sum256(contents[i])
		cids[i] = hex.EncodeToString(sum[:])
	}
	blockCids := append([]string{obraID + "-manifest"}, cids...)
	canonical := canonicalBytes(obraID, 1, blockCids)
	sig := ed25519.Sign(contentPriv, canonical)
	manifest := encodeManifest(contentPub, sig, canonical)
	return append([][]byte{manifest}, contents...)
}

// espelha Manifest.canonicalBytes() do Kotlin
func canonicalBytes(chapterID string, seq int64, blockCids []string) []byte {
	fields := [][]byte{[]byte(chapterID), i64be(seq)}
	for _, c := range blockCids {
		fields = append(fields, []byte(c))
	}
	out := u32be(uint32(len(fields)))
	for _, f := range fields {
		out = append(out, u32be(uint32(len(f)))...)
		out = append(out, f...)
	}
	return out
}

// espelha ManifestCodec.encode: [pkLen][pk][sigLen][sig][canonical]
func encodeManifest(pk, sig, canonical []byte) []byte {
	out := u32be(uint32(len(pk)))
	out = append(out, pk...)
	out = append(out, u32be(uint32(len(sig)))...)
	out = append(out, sig...)
	out = append(out, canonical...)
	return out
}

func connectBootstrap(ctx context.Context, h interface {
	Connect(context.Context, peer.AddrInfo) error
}, addr string) {
	// (usado no E3 para formar a rede)
	pi, err := peer.AddrInfoFromString(addr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "bootstrap parse: %v\n", err)
		return
	}
	c, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	if err := h.Connect(c, *pi); err != nil {
		fmt.Fprintf(os.Stderr, "bootstrap connect: %v\n", err)
	} else {
		fmt.Fprintf(os.Stderr, "bootstrap ok: %s\n", pi.ID)
	}
}

func u32be(v uint32) []byte { b := make([]byte, 4); binary.BigEndian.PutUint32(b, v); return b }
func i64be(v int64) []byte  { b := make([]byte, 8); binary.BigEndian.PutUint64(b, uint64(v)); return b }


type seedReader struct {
	seed [32]byte
	pos  int
}

func newSeedReader(seed [32]byte) *seedReader { return &seedReader{seed: seed} }
func (r *seedReader) Read(p []byte) (int, error) {
	n := 0
	for i := range p {
		p[i] = r.seed[r.pos%32]
		r.pos++
		n++
	}
	return n, nil
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "erro:", err)
		os.Exit(1)
	}
}
