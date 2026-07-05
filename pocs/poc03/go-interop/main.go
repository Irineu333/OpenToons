// Interop bônus só-go (poc-03, tarefa 5.4): um nó go-libp2p+boxo/bitswap conecta a um kubo
// local e busca um bloco por Bitswap (want/have). É o único ponto onde a POC toca o
// ecossistema IPFS de fato — o gap que o poc-01 não fechou (Amino bloqueada por bugs do nabu).
//
// uso: interop <kubo_multiaddr_com_p2p> <cid>
package main

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/ipfs/boxo/bitswap"
	bsnet "github.com/ipfs/boxo/bitswap/network"
	"github.com/ipfs/boxo/blockstore"
	"github.com/ipfs/go-cid"
	"github.com/ipfs/go-datastore"
	dssync "github.com/ipfs/go-datastore/sync"
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/peer"
	routinghelpers "github.com/libp2p/go-libp2p-routing-helpers"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "uso: interop <kubo_multiaddr> <cid>")
		os.Exit(1)
	}
	ctx := context.Background()

	kuboInfo, err := peer.AddrInfoFromString(os.Args[1])
	must(err)
	c, err := cid.Decode(os.Args[2])
	must(err)

	h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/0.0.0.0/tcp/0"))
	must(err)
	defer h.Close()

	// bitswap client sobre o host — criado ANTES de conectar, para o notifiee de rede do
	// bitswap registrar o kubo assim que a conexão subir (senão o want não é enviado a ele).
	bstore := blockstore.NewBlockstore(dssync.MutexWrap(datastore.NewMapDatastore()))
	net := bsnet.NewFromIpfsHost(h, &routinghelpers.Null{})
	bs := bitswap.New(ctx, net, bstore)
	defer bs.Close()

	// conecta ao kubo (interop de conexão: Noise + muxer + identify com o nó IPFS de fábrica)
	cctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	must(h.Connect(cctx, *kuboInfo))
	fmt.Fprintf(os.Stderr, "CONECTADO ao kubo %s\n", kuboInfo.ID)
	time.Sleep(1 * time.Second) // deixa o identify/bitswap registrarem o peer

	gctx, gcancel := context.WithTimeout(ctx, 20*time.Second)
	defer gcancel()
	fmt.Fprintf(os.Stderr, "BITSWAP want %s…\n", c)
	blk, err := bs.GetBlock(gctx, c)
	must(err)

	fmt.Fprintf(os.Stderr, "BLOCO RECEBIDO por Bitswap: %d bytes\n", len(blk.RawData()))
	fmt.Fprintf(os.Stderr, "conteúdo: %q\n", string(blk.RawData()))
	fmt.Fprintln(os.Stderr, "INTEROP OK — go-libp2p+boxo buscou bloco de um kubo real")
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "erro:", err)
		os.Exit(1)
	}
}
