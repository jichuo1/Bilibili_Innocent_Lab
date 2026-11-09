extern "C" void bil_linker_lock_fixture();

__attribute__((constructor)) static void hold_linker_lock() {
    bil_linker_lock_fixture();
}
