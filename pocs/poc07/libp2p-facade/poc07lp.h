#include <stdint.h>
#include <stddef.h>
typedef struct Poc07Node Poc07Node;
char* poc07_lp_peer_id_from_ed25519(const char* pubkey_hex);
Poc07Node* poc07_lp_new(const char* bootstrap_multiaddr);
char* poc07_lp_peer_id(Poc07Node* node);
int poc07_lp_dial(Poc07Node* node, const char* multiaddr);
char* poc07_lp_resolve(Poc07Node* node, const char* obra_id);
uint8_t* poc07_lp_get_manifest(Poc07Node* node, const char* provider, const char* obra_id, size_t* out_len);
uint8_t* poc07_lp_get_blocks(Poc07Node* node, const char* provider, const char* cids, size_t* out_len);
void poc07_lp_free_bytes(uint8_t* ptr, size_t len);
void poc07_lp_free_str(char* s);
void poc07_lp_free_node(Poc07Node* node);

/* task 5.3 — full nodes libp2p in-process (TCK: bootstrap + publicador + cliente no alvo iOS) */
typedef struct Poc07Store Poc07Store;
typedef struct Poc07Server Poc07Server;
Poc07Store* poc07_lp_store_new(void);
void poc07_lp_store_set_manifest(Poc07Store* store, const char* obra_id, const uint8_t* data, size_t len);
void poc07_lp_store_put_block(Poc07Store* store, const char* cid, const uint8_t* data, size_t len);
void poc07_lp_free_store(Poc07Store* store);
Poc07Server* poc07_lp_server_new(Poc07Store* store, const char* seed_hex, uint16_t listen_port);
char* poc07_lp_server_peer_id(Poc07Server* node);
uint16_t poc07_lp_server_listen_port(Poc07Server* node);
int poc07_lp_server_bootstrap(Poc07Server* node, const char* multiaddr);
int poc07_lp_server_start_providing(Poc07Server* node, const char* obra_id);
void poc07_lp_free_server(Poc07Server* node);
