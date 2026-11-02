#pragma once

#include <cstring>
#include <limits.h>
#include <string_view>
#include <type_traits>

namespace bil::npatch {

// A destructor-bearing TLS object registers with the linker on first use. File
// hooks must never do that while holding the path mutex: dlopen holds the linker
// lock and can reenter those same hooks on another thread.
struct RedirectPathBuffer {
    char bytes[PATH_MAX]{};

    const char* copy(std::string_view path) noexcept {
        if (path.size() >= sizeof(bytes) || path.find('\0') != std::string_view::npos) return nullptr;
        if (!path.empty()) std::memcpy(bytes, path.data(), path.size());
        bytes[path.size()] = '\0';
        return bytes;
    }
};

static_assert(std::is_trivially_destructible_v<RedirectPathBuffer>);

} // namespace bil::npatch
