# PoC poc-04 — mesmas regras do poc-02 (provider BC registrado por string) + JNA/UniFFI
# (binding gerado resolve funções nativas por reflexão de nomes).
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class com.sun.jna.** { *; }
-keep class uniffi.facade.** { *; }
-dontwarn java.awt.**
# APIs de desktop referenciadas pelo bcprov/JNA que não existem (nem são usadas) no Android
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
