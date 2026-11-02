#include "RedirectPathBuffer.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <thread>
#include <unistd.h>

namespace {
std::mutex path_mutex;
std::atomic<bool> path_held{false}, linker_held{false}, finished{false};
thread_local bil::npatch::RedirectPathBuffer buffer;
thread_local std::string legacy_buffer;

void require(bool value, const char* message) {
    if (!value) { std::fprintf(stderr, "%s\n", message); std::_Exit(1); }
}
void wait_for(const std::atomic<bool>& flag) {
    while (!flag.load(std::memory_order_acquire)) std::this_thread::yield();
}
void boundaries() {
    require(std::strcmp(buffer.copy({}), "") == 0, "empty path");
    const std::string longest(PATH_MAX - 1, 'a');
    require(std::strcmp(buffer.copy(longest), longest.c_str()) == 0, "maximum path");
    require(buffer.copy(std::string(PATH_MAX, 'b')) == nullptr, "oversized path must not truncate");
    require(buffer.copy(std::string_view("a\0b", 3)) == nullptr, "embedded NUL must not redirect");
    require(std::strcmp(buffer.copy("/original.apk"), "/original.apk") == 0, "reuse after rejection");
    std::atomic<bool> copied{false}, release{false};
    const char* other = nullptr;
    std::thread worker([&] {
        other = buffer.copy("/other.apk");
        copied.store(true, std::memory_order_release);
        wait_for(release);
        require(std::strcmp(other, "/other.apk") == 0, "thread buffer was overwritten");
    });
    wait_for(copied);
    require(other != buffer.bytes, "threads share a redirect buffer");
    require(std::strcmp(buffer.bytes, "/original.apk") == 0, "other thread overwrote path");
    release.store(true, std::memory_order_release);
    worker.join();
}
} // namespace

// Called by a dlopen constructor while Android's linker lock is held.
extern "C" __attribute__((visibility("default"))) void bil_linker_lock_fixture() {
    linker_held.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lock(path_mutex);
}

int main(int argc, char** argv) {
    require(argc == 3, "usage: test <fixture.so> fixed|legacy");
    const bool legacy = std::strcmp(argv[2], "legacy") == 0;
    boundaries();
    std::thread path_owner([&] {
        std::lock_guard<std::mutex> lock(path_mutex);
        path_held.store(true, std::memory_order_release);
        wait_for(linker_held);
        if (legacy) legacy_buffer = "/original.apk";
        else require(buffer.copy("/original.apk") != nullptr, "locked copy failed");
    });
    std::thread library_loader([&] {
        wait_for(path_held);
        void* handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
        require(handle != nullptr, dlerror());
        dlclose(handle);
        path_owner.join();
        finished.store(true, std::memory_order_release);
    });
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!finished.load(std::memory_order_acquire) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    if (!finished.load(std::memory_order_acquire)) {
        constexpr char message[] = "linker/path lock inversion reproduced\n";
        write(STDOUT_FILENO, message, sizeof(message) - 1);
        std::_Exit(42);
    }
    library_loader.join();
    std::puts("redirect boundaries, thread isolation and linker lock regression passed");
}
