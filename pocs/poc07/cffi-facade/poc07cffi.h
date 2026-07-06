#include <stdint.h>
#include <stddef.h>
int poc07_cffi_add(int a, int b);
char* poc07_cffi_sha256_hex(const uint8_t* data, size_t len);
void poc07_cffi_free(char* s);
