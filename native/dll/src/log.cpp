#include "naven.h"

#include <cstdarg>
#include <cstdio>
#include <cwchar>
#include <mutex>

namespace naven::log {

namespace {
    std::mutex g_mutex;
    HANDLE g_file = INVALID_HANDLE_VALUE;

    void write_line(const char* level, const char* fmt, va_list ap) {
        std::lock_guard<std::mutex> lock(g_mutex);

        SYSTEMTIME st;
        GetLocalTime(&st);

        char buf[2048];
        int prefix = std::snprintf(buf, sizeof buf, "[%04d-%02d-%02d %02d:%02d:%02d.%03d %s] ",
                                   st.wYear, st.wMonth, st.wDay,
                                   st.wHour, st.wMinute, st.wSecond,
                                   st.wMilliseconds, level);
        if (prefix < 0) prefix = 0;
        int body = std::vsnprintf(buf + prefix, sizeof buf - prefix - 2, fmt, ap);
        if (body < 0) body = 0;
        int total = prefix + body;
        if (total > (int)sizeof buf - 2) total = (int)sizeof buf - 2;
        buf[total++] = '\r';
        buf[total++] = '\n';

        // Visible in DebugView / WinDbg even when the temp file is unavailable.
        OutputDebugStringA(buf);

        if (g_file != INVALID_HANDLE_VALUE) {
            DWORD written = 0;
            WriteFile(g_file, buf, (DWORD)total, &written, nullptr);
            // No buffering: if the game crashes mid-bootstrap the log still
            // holds everything up to that point.
            FlushFileBuffers(g_file);
        }
    }
}

namespace {
    HANDLE open_log(const wchar_t* path, DWORD share) {
        return CreateFileW(path, GENERIC_WRITE, share, nullptr,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    }
}

void init() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_file != INVALID_HANDLE_VALUE) return;

    wchar_t tmp[MAX_PATH];
    DWORD n = GetTempPathW(MAX_PATH, tmp);
    if (n == 0 || n >= MAX_PATH) return;

    wchar_t path[MAX_PATH];
    std::swprintf(path, MAX_PATH, L"%snaven.log", tmp);

    // A log viewer (or our own injector tailing the file) can hold naven.log
    // for a moment right after injection. Retry briefly instead of silently
    // degrading to OutputDebugString-only logging.
    for (int attempt = 0; attempt < 40; ++attempt) {
        g_file = open_log(path, FILE_SHARE_READ | FILE_SHARE_WRITE);
        if (g_file != INVALID_HANDLE_VALUE) return;
        DWORD err = GetLastError();
        if (err != ERROR_SHARING_VIOLATION && err != ERROR_ACCESS_DENIED) return;
        Sleep(25);
    }

    // Still locked: fall back to a per-process file so diagnostics survive.
    wchar_t fallback[MAX_PATH];
    std::swprintf(fallback, MAX_PATH, L"%snaven-%lu.log", tmp, GetCurrentProcessId());
    g_file = open_log(fallback, FILE_SHARE_READ | FILE_SHARE_WRITE);
}

void info(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    write_line("INFO", fmt, ap);
    va_end(ap);
}

void error(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    write_line("ERROR", fmt, ap);
    va_end(ap);
}

} // namespace naven::log
