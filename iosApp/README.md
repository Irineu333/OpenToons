# OpenToons — app iOS (leitor)

A UI e a lógica do leitor vivem no módulo `:shared` (Compose Multiplatform). O iOS consome
o `shared` como **framework estático `Shared`** e embute o Compose via `MainViewController`
(`shared/src/iosMain/.../MainViewController.kt`).

O projeto Xcode é **gerado pelo [xcodegen](https://github.com/yonaskolb/XcodeGen)** a partir
de `project.yml` (o `.xcodeproj` não é versionado). Fontes Swift em `iosApp/`.

## Rodar no simulador

```sh
brew install xcodegen           # se necessário
cd iosApp
xcodegen generate               # gera iosApp.xcodeproj a partir de project.yml
xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build

xcrun simctl boot "iPhone 16"                 # se não estiver bootado
xcrun simctl install "iPhone 16" build/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl launch "iPhone 16" com.neoutils.opentoons
open -a Simulator
```

O pre-build script do target roda `./gradlew :shared:embedAndSignAppleFrameworkForXcode`,
que compila o framework Kotlin/Native e o coloca em
`shared/build/xcode-frameworks/$CONFIGURATION/$SDK_NAME/`.

## ⚠️ Requisito de toolchain (Xcode)

O **Compose MP 1.11.1 referencia `UIViewLayoutRegion`**, uma classe de UIKit presente apenas
em um SDK do iOS **mais novo que o do Xcode 16.4 / SDK 18.5** deste ambiente. Consequências:

- `deploymentTarget` do app está em **iOS 18.0** (a versão do símbolo).
- Há um workaround em `project.yml` permitindo o símbolo como *undefined* (lookup dinâmico):
  `-Wl,-U,_OBJC_CLASS_$_UIViewLayoutRegion`. Sem ele, o link falha em Xcode 16.4.
- Com esse workaround o app **builda e roda no simulador iOS 18.5** (biblioteca, tema, Room,
  navegação verificados). Se algum caminho do Compose usar de fato essa classe e ela não
  existir em runtime, pode quebrar — a solução definitiva é **atualizar o Xcode** para a versão
  que o Compose MP 1.11.1 espera (então o workaround pode ser removido).
- O Compose MP também exige `CADisableMinimumFrameDurationOnPhone=true` no `Info.plist` (já
  incluído).

## Build só do framework (sanity, sem Xcode)

```sh
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared:compileKotlinIosSimulatorArm64
```
