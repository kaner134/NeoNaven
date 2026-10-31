#include "naven.h"
#include "resource.h"

namespace naven::jar {

namespace {

// Walk the PE resource tree of the in-memory image instead of calling
// FindResource/LoadResource: the DLL may have been injected by a manual mapper
// and therefore have no LDR_DATA_TABLE_ENTRY, which is what FindResource
// resolves the module handle through.
const void* find_rcdata(HMODULE module_base, WORD id, DWORD& out_size) {
    out_size = 0;
    auto base = reinterpret_cast<BYTE*>(module_base);
    auto dos = reinterpret_cast<PIMAGE_DOS_HEADER>(base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return nullptr;
    auto nt = reinterpret_cast<PIMAGE_NT_HEADERS>(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return nullptr;

    const auto& res_dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_RESOURCE];
    if (res_dir.Size == 0) return nullptr;
    BYTE* root_base = base + res_dir.VirtualAddress;

    auto find_id_child = [&](PIMAGE_RESOURCE_DIRECTORY dir, WORD wanted_id)
            -> PIMAGE_RESOURCE_DIRECTORY_ENTRY {
        auto entry = reinterpret_cast<PIMAGE_RESOURCE_DIRECTORY_ENTRY>(dir + 1);
        WORD total = dir->NumberOfNamedEntries + dir->NumberOfIdEntries;
        // ID entries follow the named entries.
        for (WORD i = dir->NumberOfNamedEntries; i < total; ++i) {
            auto e = entry + i;
            if (!e->NameIsString && e->Id == wanted_id) return e;
        }
        return nullptr;
    };

    // Level 1: resource type (RT_RCDATA = 10).
    auto root = reinterpret_cast<PIMAGE_RESOURCE_DIRECTORY>(root_base);
    auto type_entry = find_id_child(root, 10);
    if (!type_entry || !type_entry->DataIsDirectory) return nullptr;

    // Level 2: resource name (our integer id).
    auto name_dir = reinterpret_cast<PIMAGE_RESOURCE_DIRECTORY>(
            root_base + type_entry->OffsetToDirectory);
    auto name_entry = find_id_child(name_dir, id);
    if (!name_entry || !name_entry->DataIsDirectory) return nullptr;

    // Level 3: language. Take the first available.
    auto lang_dir = reinterpret_cast<PIMAGE_RESOURCE_DIRECTORY>(
            root_base + name_entry->OffsetToDirectory);
    WORD lang_count = lang_dir->NumberOfNamedEntries + lang_dir->NumberOfIdEntries;
    if (lang_count == 0) return nullptr;
    auto lang_entry = reinterpret_cast<PIMAGE_RESOURCE_DIRECTORY_ENTRY>(lang_dir + 1);
    auto data_entry = reinterpret_cast<PIMAGE_RESOURCE_DATA_ENTRY>(
            root_base + lang_entry->OffsetToData);

    out_size = data_entry->Size;
    return base + data_entry->OffsetToData;
}

bool extract(WORD id, const wchar_t* stem, const char* label, std::wstring& out_path) {
    DWORD size = 0;
    const void* data = find_rcdata(g_self_module, id, size);
    if (!data || size == 0) {
        log::error("PE resource %s (RT_RCDATA id %u) is missing or empty; "
                   "was the jar staged before building the DLL?", label, (unsigned)id);
        return false;
    }

    wchar_t tmp[MAX_PATH];
    if (GetTempPathW(MAX_PATH, tmp) == 0) {
        log::error("GetTempPathW failed: %lu", GetLastError());
        return false;
    }

    // Unique per injection: the JVM keeps the previously loaded jar open, so a second
    // injection into the same process must not reuse (and fail to overwrite) that path.
    wchar_t path[MAX_PATH];
    std::swprintf(path, MAX_PATH, L"%s%s-%lu-%llu.jar", tmp, stem, GetCurrentProcessId(),
                  (unsigned long long)GetTickCount64());

    HANDLE file = CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        log::error("CreateFileW(%ls) failed: %lu", path, GetLastError());
        return false;
    }

    DWORD written = 0;
    BOOL ok = WriteFile(file, data, size, &written, nullptr);
    CloseHandle(file);
    if (!ok || written != size) {
        log::error("WriteFileW(%ls) failed: %lu", path, GetLastError());
        return false;
    }

    out_path.assign(path);
    log::info("Extracted %s (%lu bytes) to %ls", label, (unsigned long)size, path);
    return true;
}

} // namespace

bool extract_client(std::wstring& out_path) {
    return extract(IDR_NAVEN_JAR, L"naven", "client jar", out_path);
}

bool extract_agent(std::wstring& out_path) {
    return extract(IDR_NAVEN_AGENT_JAR, L"naven-agent", "agent stub jar", out_path);
}

} // namespace naven::jar