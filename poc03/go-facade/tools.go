//go:build tools

// Mantém golang.org/x/mobile no go.mod para o `gomobile bind` sobreviver ao `go mod tidy`
// (o cmd/publisher não usa x/mobile, então sem isto o tidy o remove e o bind falha).
package facade

import _ "golang.org/x/mobile/bind"
