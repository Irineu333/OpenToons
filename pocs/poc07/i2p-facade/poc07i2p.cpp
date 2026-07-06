// poc-07 célula 3 — implementação do C-ABI sobre o libi2pd embarcado (api.h + Streaming).
// Router I2P in-process no iPhone: reseed → tunnels → netDB. Cliente descobre a destination
// remota por LeaseSet (lookup na netDB, garlic) e streama o capítulo. O verify fica no Kotlin.

#include "poc07i2p.h"

#include "api.h"
#include "Identity.h"
#include "Destination.h"
#include "Streaming.h"
#include "Base.h"
#include "Log.h"
#include "Config.h"
#include "FS.h"
#include "Crypto.h"
#include "RouterContext.h"
#include "Transports.h"

#include <memory>
#include <string>
#include <cstring>
#include <thread>
#include <chrono>
#include <atomic>
#include <cstdio>
#include <iostream>

// trace por etapa (stderr, flushed) — capturado por devicectl --console p/ localizar travas.
#define P7LOG(...) do { fprintf(stderr, "POC07I2P-STEP " __VA_ARGS__); fprintf(stderr, "\n"); fflush(stderr); } while (0)

namespace {
    std::shared_ptr<i2p::client::ClientDestination> g_dest;
    std::atomic<bool> g_started{false};
}

struct Poc07Stream {
    std::shared_ptr<i2p::stream::Stream> stream;
};

extern "C" {

int poc07_i2p_start(const char* datadir) {
    if (g_started.load()) return 0;
    try {
        std::string dd = datadir ? datadir : "";
        // argv mínimo p/ o Config do i2pd: datadir gravável + sem serviços de escuta/proxies
        // (só cliente). floodfill off; ntcp2/ssu2 saída ligados por padrão.
        // args MÍNIMOS: só datadir + log. Vários bool do i2pd são bool_switch (presença=true,
        // NÃO aceitam "=valor") — passar "=false" aborta o parse. Os defaults (proxies em
        // localhost) são inofensivos p/ o teste; o que importa é o router + a destination via API.
        std::string argDatadir = std::string("--datadir=") + dd;
        std::string argLog = std::string("--logfile=") + dd + "/poc07i2p.log";
        std::string args[] = {
            "poc07i2p",
            argDatadir,
            "--log=file",
            argLog,
            "--loglevel=info",
        };
        int argc = (int)(sizeof(args) / sizeof(args[0]));
        char* argv[sizeof(args) / sizeof(args[0])];
        for (int i = 0; i < argc; i++) argv[i] = const_cast<char*>(args[i].c_str());

        // InitI2P inlined com trace por sub-passo (localiza travas/exit silenciosos no iOS).
        P7LOG("InitI2P begin datadir=%s", dd.c_str());
        i2p::config::Init();
        P7LOG("  config.ParseCmdline");
        i2p::config::ParseCmdline(argc, argv, true);
        P7LOG("  config.Finalize");
        i2p::config::Finalize();
        std::string ddir; i2p::config::GetOption("datadir", ddir);
        P7LOG("  fs.SetAppName/Detect datadir=%s", ddir.c_str());
        i2p::fs::SetAppName("poc07i2p");
        i2p::fs::DetectDataDir(ddir, false);
        P7LOG("  fs.Init");
        i2p::fs::Init();
        // o InitI2P da api NÃO chama SetCertsDir → certsDir fica "" e o reseed não acha os certs
        // ("Can't load reseed certificates from /reseed"). Aponta p/ <datadir>/certificates.
        i2p::fs::SetCertsDir("");
        P7LOG("  certsDir=%s", i2p::fs::GetCertsDir().c_str());
        bool precomp = false; i2p::config::GetOption("precomputation.elgamal", precomp);
        P7LOG("  crypto.InitCrypto precomp=%d", (int)precomp);
        i2p::crypto::InitCrypto(precomp);
        int netID = 2; i2p::config::GetOption("netid", netID);
        i2p::context.SetNetID(netID);
        bool checkReserved = true; i2p::config::GetOption("reservedrange", checkReserved);
        i2p::transport::transports.SetCheckReserved(checkReserved);
        P7LOG("  context.Init");
        i2p::context.Init();
        P7LOG("InitI2P end");

        // roteia o log do i2pd p/ std::cout (capturado pelo console) — StartI2P força SendTo.
        auto logStream = std::shared_ptr<std::ostream>(&std::cout, [](std::ostream*) {});
        P7LOG("StartI2P begin");
        i2p::api::StartI2P(logStream);
        P7LOG("StartI2P end");

        // destination cliente transiente (não publicada), assinatura Ed25519.
        P7LOG("CreateLocalDestination begin");
        g_dest = i2p::api::CreateLocalDestination(false, i2p::data::SIGNING_KEY_TYPE_EDDSA_SHA512_ED25519);
        P7LOG("CreateLocalDestination end dest=%p", (void*)g_dest.get());
        if (!g_dest) return -1;
        g_started.store(true);
        return 0;
    } catch (const std::exception& e) {
        LogPrint(eLogError, "POC07I2P: start falhou: ", e.what());
        return -2;
    } catch (...) {
        return -3;
    }
}

int poc07_i2p_ready(void) {
    if (!g_dest) return 0;
    int r = g_dest->IsReady() ? 1 : 0;
    static int calls = 0;
    if ((calls++ % 5) == 0) P7LOG("ready? = %d (call %d)", r, calls);
    return r;
}

Poc07Stream* poc07_i2p_connect(const char* b32, int timeout_ms) {
    if (!g_dest || !b32) return nullptr;
    try {
        std::string addr(b32);
        // aceita "<52>.b32.i2p", "<52>.b32" ou só "<52>"
        auto pos = addr.find(".b32");
        if (pos != std::string::npos) addr = addr.substr(0, pos);

        uint8_t hash[32];
        size_t decoded = i2p::data::Base32ToByteStream(addr, hash, 32);
        if (decoded != 32) {
            LogPrint(eLogError, "POC07I2P: b32 inválido (", addr, ")");
            return nullptr;
        }
        i2p::data::IdentHash ident(hash);

        // descoberta: pede o LeaseSet remoto e faz poll até chegar pela netDB.
        g_dest->RequestDestination(ident);
        std::shared_ptr<const i2p::data::LeaseSet> ls;
        int waited = 0;
        const int step = 500;
        while (waited < timeout_ms) {
            ls = g_dest->FindLeaseSet(ident);
            if (ls) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(step));
            waited += step;
            if (waited % 5000 == 0) g_dest->RequestDestination(ident); // re-request periódico
        }
        if (!ls) {
            LogPrint(eLogError, "POC07I2P: LeaseSet não resolvido em ", timeout_ms, "ms");
            return nullptr;
        }

        auto stream = g_dest->CreateStream(ls, 0);
        if (!stream) return nullptr;
        stream->Send(nullptr, 0); // SYN (connect)

        auto* h = new Poc07Stream();
        h->stream = stream;
        return h;
    } catch (const std::exception& e) {
        LogPrint(eLogError, "POC07I2P: connect falhou: ", e.what());
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

int poc07_i2p_send(Poc07Stream* h, const uint8_t* data, size_t len) {
    if (!h || !h->stream) return -1;
    try {
        h->stream->Send(data, len);
        return 0;
    } catch (...) {
        return -2;
    }
}

long poc07_i2p_recv(Poc07Stream* h, uint8_t* buf, size_t len, int timeout_s) {
    if (!h || !h->stream) return -1;
    try {
        return (long)h->stream->Receive(buf, len, timeout_s);
    } catch (...) {
        return -2;
    }
}

void poc07_i2p_close(Poc07Stream* h) {
    if (!h) return;
    if (h->stream) i2p::api::DestroyStream(h->stream);
    delete h;
}

void poc07_i2p_stop(void) {
    if (!g_started.load()) return;
    if (g_dest) { i2p::api::DestroyLocalDestination(g_dest); g_dest.reset(); }
    i2p::api::StopI2P();
    g_started.store(false);
}

} // extern "C"
