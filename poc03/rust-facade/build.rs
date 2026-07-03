// Gera o scaffolding UniFFI a partir do procmacro (#[uniffi::export] em lib.rs).
fn main() {
    uniffi::generate_scaffolding("src/facade.udl").unwrap();
}
