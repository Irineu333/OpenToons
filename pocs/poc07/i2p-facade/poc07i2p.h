#ifndef POC07I2P_H
#define POC07I2P_H
#include <stdint.h>
#include <stddef.h>

/*
 * poc-07 célula 3 (E2E I2P no iPhone) — C-ABI mínimo sobre o libi2pd EMBARCADO in-process
 * (api.h + Streaming), o MESMO mecanismo cinterop já provado na libp2p. O router I2P sobe
 * DENTRO do app iOS (reseed, tunnels, netDB); o leitor descobre a destination .b32 do servidor
 * pela rede I2P (lookup de LeaseSet) e baixa o capítulo por um stream garlic-routed. O verify
 * Ed25519+sha256 continua FORA do seam (Kotlin), como nos outros backends.
 */

#ifdef __cplusplus
extern "C" {
#endif

typedef struct Poc07Stream Poc07Stream;

/* Sobe o router I2P embarcado (datadir gravável p/ netdb/certs/keys) + cria uma destination
   cliente transiente. 0 = ok, <0 = erro. Idempotente. */
int poc07_i2p_start(const char* datadir);

/* 1 quando o router tem tunnels de saída + leaseset local (pronto p/ discar); 0 caso contrário. */
int poc07_i2p_ready(void);

/* Descobre o LeaseSet da destination remota (.b32, com ou sem sufixo) pela netDB e abre um
   stream I2P. Bloqueia até timeout_ms. Retorna handle ou NULL. */
Poc07Stream* poc07_i2p_connect(const char* b32, int timeout_ms);

/* Escreve len bytes no stream. 0 = ok, <0 = erro. */
int poc07_i2p_send(Poc07Stream* s, const uint8_t* data, size_t len);

/* Lê até len bytes (bloqueante, timeout em SEGUNDOS). Retorna nº lido, 0 = timeout/EOF, <0 = erro. */
long poc07_i2p_recv(Poc07Stream* s, uint8_t* buf, size_t len, int timeout_s);

/* Fecha e libera o stream. */
void poc07_i2p_close(Poc07Stream* s);

/* Encerra o router I2P. */
void poc07_i2p_stop(void);

#ifdef __cplusplus
}
#endif
#endif
