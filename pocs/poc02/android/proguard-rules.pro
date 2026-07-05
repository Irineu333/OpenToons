# O provider JCA do BouncyCastle registra os SPIs por nome de classe (strings),
# invisível ao R8 — manter o provider inteiro; o lightweight API é alcançável normalmente.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
