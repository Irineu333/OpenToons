// Package facade expõe a superfície FFI mínima do poc-03 sobre go-libp2p de referência.
//
// Superfície (E2, design D2/D3): dial / resolve / getBlocks. A quarta chamada do
// contrato — verify — NÃO cruza a fronteira (D7): a verificação Ed25519 + hash é feita
// em Kotlin, do lado do app; este facade só entrega bytes.
//
// Restrições de `gomobile bind`: os tipos que atravessam a fronteira são planos
// (string, int, []byte, structs simples). Nada de generics, slices de struct nem
// canais expostos. CIDs entram/saem como string separada por '\n' e blocos como um
// buffer length-prefixed — o marshalling fica trivial para o gerador.
package facade

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"

	"github.com/ipfs/go-cid"
	mh "github.com/multiformats/go-multihash"

	"github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/core/routing"
	"github.com/libp2p/go-libp2p/p2p/security/noise"
	libp2pquic "github.com/libp2p/go-libp2p/p2p/transport/quic"
	libp2ptcp "github.com/libp2p/go-libp2p/p2p/transport/tcp"
	"github.com/multiformats/go-multiaddr"
)

// blockProtocol é o Request-Response de blocos (D3): paridade com o rust-facade, que
// usa a mesma superfície. É o único protocolo de app; descoberta é Kademlia (E3).
const blockProtocol protocol.ID = "/opentoons/blocks/1.0.0"

const (
	dialTimeout    = 15 * time.Second
	requestTimeout = 30 * time.Second
)

// Node é o handle opaco do nó libp2p exposto ao Kotlin. Um único objeto atravessa a
// fronteira; os métodos são as chamadas FFI. Modo client puro (ADR-0005) é o default:
// sem servidor de blocos, sem modo server do DHT, sem escuta de entrada.
type Node struct {
	host host.Host
	dht  *dht.IpfsDHT
	ctx  context.Context
	stop context.CancelFunc

	mu    sync.Mutex
	dials map[peer.ID]struct{}
}

// NewClientNode inicializa um nó em modo client (E3/6.3): descobre e disca, mas não
// armazena registros de terceiros, não roteia e não aceita conexões de entrada.
// bootstrapMultiaddr é o único ponto de entrada frio (E4). Retorna o Node ou erro —
// gomobile mapeia (*Node, error) para exceção no Kotlin.
func NewClientNode(bootstrapMultiaddr string) (*Node, error) {
	ctx, cancel := context.WithCancel(context.Background())

	h, err := libp2p.New(
		libp2p.NoListenAddrs, // client puro: sem endereços de escuta (ADR-0005)
		libp2p.Security(noise.ID, noise.New),
		libp2p.Transport(libp2ptcp.NewTCPTransport),
		libp2p.Transport(libp2pquic.NewTransport),
		libp2p.EnableRelay(),   // permite ser discado via relay (dado de NAT, E5/8.4)
		libp2p.EnableHolePunching(), // DCUtR (dado só-coletado E5/8.4, TCP)
	)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("libp2p host: %w", err)
	}

	// ModeClient: o nó consulta a DHT mas não vira servidor dela (não guarda a tabela
	// de providers de terceiros). É exatamente o requisito do cliente do E3.
	kdht, err := dht.New(ctx, h, dht.Mode(dht.ModeClient))
	if err != nil {
		_ = h.Close()
		cancel()
		return nil, fmt.Errorf("kad-dht: %w", err)
	}

	n := &Node{host: h, dht: kdht, ctx: ctx, stop: cancel, dials: map[peer.ID]struct{}{}}

	if bootstrapMultiaddr != "" {
		if err := n.connectBootstrap(bootstrapMultiaddr); err != nil {
			_ = n.Close()
			return nil, err
		}
	}
	if err := kdht.Bootstrap(ctx); err != nil {
		_ = n.Close()
		return nil, fmt.Errorf("dht bootstrap: %w", err)
	}
	return n, nil
}

// PeerID devolve o id base58 do nó — usado no relatório e nos logs do app.
func (n *Node) PeerID() string { return n.host.ID().String() }

func (n *Node) connectBootstrap(addr string) error {
	pi, err := peerInfo(addr)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(n.ctx, dialTimeout)
	defer cancel()
	if err := n.host.Connect(ctx, *pi); err != nil {
		return fmt.Errorf("bootstrap connect: %w", err)
	}
	return nil
}

// Dial (chamada FFI 1) — abre conexão a um multiaddr completo (com /p2p/<id>).
// Mede o handshake do lado nativo; a latência é reportada pelo app (E5/8.2).
func (n *Node) Dial(multiaddrWithPeer string) error {
	pi, err := peerInfo(multiaddrWithPeer)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(n.ctx, dialTimeout)
	defer cancel()
	if err := n.host.Connect(ctx, *pi); err != nil {
		return fmt.Errorf("dial %s: %w", pi.ID, err)
	}
	n.mu.Lock()
	n.dials[pi.ID] = struct{}{}
	n.mu.Unlock()
	return nil
}

// Resolve (chamada FFI 2) — descoberta fria via Kademlia real (E3/6.2): a partir só do
// bootstrap + obraId, acha o provider e devolve seu multiaddr discável. O obraId é
// tratado como uma CID de conteúdo (chave da DHT de providers).
func (n *Node) Resolve(obraID string) (string, error) {
	c, err := parseCID(obraID)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(n.ctx, requestTimeout)
	defer cancel()

	// FindProvidersAsync devolve o primeiro provider assim que a busca converge —
	// mede-se o nº de RTTs contra o limiar ≤ 3 (E3/6.2) no lado do app.
	providers := n.dht.FindProvidersAsync(ctx, c, 1)
	select {
	case p, ok := <-providers:
		if !ok || len(p.Addrs) == 0 {
			return "", fmt.Errorf("nenhum provider para %s", obraID)
		}
		full := p.Addrs[0].Encapsulate(multiaddr.StringCast("/p2p/" + p.ID.String()))
		return full.String(), nil
	case <-ctx.Done():
		return "", fmt.Errorf("resolve %s: %w", obraID, ctx.Err())
	}
}

// GetBlocks (chamada FFI 3) — baixa manifesto + blocos por Request-Response (D3).
// cids: chaves separadas por '\n' (a 1ª é o manifesto). Devolve os blocos concatenados
// com length-prefix uint32 big-endian, na mesma ordem — o Kotlin fatia, verifica o
// hash de cada bloco e a assinatura do manifesto (D7, fora da fronteira FFI).
func (n *Node) GetBlocks(peerMultiaddr string, cids string) ([]byte, error) {
	pi, err := peerInfo(peerMultiaddr)
	if err != nil {
		return nil, err
	}
	keys := splitKeys(cids)
	if len(keys) == 0 {
		return nil, fmt.Errorf("nenhum cid pedido")
	}

	ctx, cancel := context.WithTimeout(n.ctx, requestTimeout)
	defer cancel()

	s, err := n.host.NewStream(network.WithAllowLimitedConn(ctx, "blocks"), pi.ID, blockProtocol)
	if err != nil {
		return nil, fmt.Errorf("abrir stream: %w", err)
	}
	defer s.Close()

	// Protocolo mínimo: enviamos as chaves (uma por linha) + '\n\n'; o servidor responde
	// cada bloco length-prefixed na ordem pedida.
	if _, err := io.WriteString(s, cids+"\n\n"); err != nil {
		return nil, fmt.Errorf("enviar pedido: %w", err)
	}
	if err := s.CloseWrite(); err != nil {
		return nil, fmt.Errorf("close-write: %w", err)
	}

	// lê blocos length-prefixed até o servidor fechar a escrita (EOF) — o servidor manda
	// manifesto + N blocos e dá CloseWrite; não dependemos da contagem de cids pedidos.
	_ = keys
	out := make([]byte, 0, 1<<16)
	var lenBuf [4]byte
	for {
		if _, err := io.ReadFull(s, lenBuf[:]); err != nil {
			if err == io.EOF {
				break
			}
			return nil, fmt.Errorf("ler tamanho do bloco: %w", err)
		}
		blockLen := binary.BigEndian.Uint32(lenBuf[:])
		block := make([]byte, blockLen)
		if _, err := io.ReadFull(s, block); err != nil {
			return nil, fmt.Errorf("ler bloco: %w", err)
		}
		out = append(out, lenBuf[:]...)
		out = append(out, block...)
	}
	return out, nil
}

// Close libera o nó — chamado no lifecycle do app (E2/5.3). Idempotente.
func (n *Node) Close() error {
	n.stop()
	if n.dht != nil {
		_ = n.dht.Close()
	}
	return n.host.Close()
}

// --- helpers (não cruzam a fronteira) ---

func peerInfo(addr string) (*peer.AddrInfo, error) {
	ma, err := multiaddr.NewMultiaddr(strings.TrimSpace(addr))
	if err != nil {
		return nil, fmt.Errorf("multiaddr inválido %q: %w", addr, err)
	}
	pi, err := peer.AddrInfoFromP2pAddr(ma)
	if err != nil {
		return nil, fmt.Errorf("addrinfo %q: %w", addr, err)
	}
	return pi, nil
}

// parseCID trata o obraId como chave de conteúdo da DHT. Aceita uma CID já formada
// (base32/base58) ou, para o capítulo de teste do poc-01, deriva uma CIDv1 raw do
// hash sha2-256 do obraId — mantendo a chave estável entre publicador e cliente.
func parseCID(obraID string) (cid.Cid, error) {
	if c, err := cid.Decode(strings.TrimSpace(obraID)); err == nil {
		return c, nil
	}
	h, err := mh.Sum([]byte(obraID), mh.SHA2_256, -1)
	if err != nil {
		return cid.Undef, fmt.Errorf("hash do obraId %q: %w", obraID, err)
	}
	return cid.NewCidV1(cid.Raw, h), nil
}

func splitKeys(cids string) []string {
	raw := strings.Split(strings.TrimSpace(cids), "\n")
	out := raw[:0]
	for _, k := range raw {
		if k = strings.TrimSpace(k); k != "" {
			out = append(out, k)
		}
	}
	return out
}

// _ garante que routing continua importado (usado pela assinatura do dht.Bootstrap em
// versões futuras); mantido explícito para o leitor do facade.
var _ routing.Routing = (*dht.IpfsDHT)(nil)
