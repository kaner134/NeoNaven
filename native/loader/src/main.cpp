// Naven injector - layered, antialiased Win32 window (GDI+ on a 32bpp DIB).
//
// 480x260, rounded corners with real per-pixel alpha (no aliased region clipping),
// centred NAVEN wordmark, one fluorescent-green INJECT button, and a thin rounded
// progress bar that replaces the button while injecting. Closes itself once the
// client reports a successful bootstrap.
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#include <windows.h>
#include <windowsx.h>
#include <objbase.h>
#include <wbemidl.h>

#include <gdiplus.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "resource.h"

#pragma comment(lib, "dwmapi.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "wbemuuid.lib")
#pragma comment(lib, "gdiplus.lib")

using namespace Gdiplus;

namespace {

// ---------------------------------------------------------------------------
// theme
// ---------------------------------------------------------------------------
const ARGB kBgTop      = 0xFF121215;
const ARGB kBgBottom   = 0xFF0A0A0C;
const ARGB kBorder     = 0xFF2E2E34;
const ARGB kText       = 0xFFF0F0F2;
const ARGB kFaint      = 0xFF4E5056;
const ARGB kGreen      = 0xFF38F082;
const ARGB kGreenHot   = 0xFF60FFA0;
const ARGB kButtonText = 0xFF080E0A;
const ARGB kTrack      = 0xFF202024;
const ARGB kBar        = 0xFF3CD684;
const ARGB kRed        = 0xFFFF6060;

const wchar_t* kClass = L"NavenInjectorWindow";
const UINT_PTR kTimerRefresh = 1;
const UINT_PTR kTimerQuit = 2;

const int kWidth = 640;
const int kHeight = 380;
const int kRadius = 9;

// ---------------------------------------------------------------------------
// process discovery
// ---------------------------------------------------------------------------
struct ProcInfo {
    DWORD pid = 0;
    std::wstring cmd;
    int score = 0;
};

std::wstring lower(std::wstring s) {
    for (auto& c : s) c = (wchar_t)towlower(c);
    return s;
}

std::vector<ProcInfo> queryJavaProcesses() {
    std::vector<ProcInfo> out;
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool com = SUCCEEDED(hr);

    IWbemLocator* locator = nullptr;
    IWbemServices* services = nullptr;
    IEnumWbemClassObject* enumerator = nullptr;

    do {
        if (FAILED(CoCreateInstance(CLSID_WbemLocator, nullptr, CLSCTX_INPROC_SERVER,
                                    IID_IWbemLocator, (void**)&locator))) break;
        if (FAILED(locator->ConnectServer(SysAllocString(L"ROOT\\CIMV2"), nullptr, nullptr,
                                          nullptr, 0, nullptr, nullptr, &services))) break;
        CoSetProxyBlanket(services, RPC_C_AUTHN_WINNT, RPC_C_AUTHZ_NONE, nullptr,
                          RPC_C_AUTHN_LEVEL_CALL, RPC_C_IMP_LEVEL_IMPERSONATE, nullptr, EOAC_NONE);
        if (FAILED(services->ExecQuery(
                SysAllocString(L"WQL"),
                SysAllocString(L"SELECT ProcessId, CommandLine FROM Win32_Process "
                               L"WHERE Name='java.exe' OR Name='javaw.exe'"),
                WBEM_FLAG_FORWARD_ONLY | WBEM_FLAG_RETURN_IMMEDIATELY, nullptr, &enumerator)))
            break;

        for (;;) {
            IWbemClassObject* obj = nullptr;
            ULONG returned = 0;
            if (enumerator->Next(WBEM_INFINITE, 1, &obj, &returned) != S_OK || !obj) break;
            VARIANT vPid, vCmd;
            VariantInit(&vPid); VariantInit(&vCmd);
            ProcInfo info;
            if (SUCCEEDED(obj->Get(L"ProcessId", 0, &vPid, nullptr, nullptr)) && vPid.vt == VT_I4)
                info.pid = (DWORD)vPid.lVal;
            if (SUCCEEDED(obj->Get(L"CommandLine", 0, &vCmd, nullptr, nullptr)) && vCmd.vt == VT_BSTR)
                info.cmd = vCmd.bstrVal;
            VariantClear(&vPid); VariantClear(&vCmd);
            obj->Release();

            std::wstring lc = lower(info.cmd);
            if (lc.find(L"1.21.10") != std::wstring::npos) info.score += 400;
            if (lc.find(L"neoforge") != std::wstring::npos) info.score += 200;
            if (lc.find(L"gradle") != std::wstring::npos) info.score -= 300;
            if (lc.find(L"hmcl") != std::wstring::npos) info.score -= 500;
            if (info.pid && info.score > 0) out.push_back(info);
        }
    } while (false);

    if (enumerator) enumerator->Release();
    if (services) services->Release();
    if (locator) locator->Release();
    if (com) CoUninitialize();
    return out;
}

// ---------------------------------------------------------------------------
// state
// ---------------------------------------------------------------------------
enum class Stage { Idle, Running, Done, Failed };

struct AppState {
    HWND hwnd = nullptr;
    DWORD pid = 0;
    bool hotButton = false;
    bool hotClose = false;
    Stage stage = Stage::Idle;
    float progress = 0.0f;
    std::wstring detail;
};

AppState g;
std::atomic<bool> g_refreshing{false};

// layered-window back buffer
HDC g_memDc = nullptr;
HBITMAP g_dib = nullptr;
HBITMAP g_oldBitmap = nullptr;
void* g_bits = nullptr;
bool g_gdiplus = false;
ULONG_PTR g_gdiplusToken = 0;

// ---------------------------------------------------------------------------
// dll payload
// ---------------------------------------------------------------------------
std::wstring embeddedDllPath(std::wstring& error) {
    HRSRC res = FindResourceW(nullptr, MAKEINTRESOURCEW(IDR_NAVEN_DLL), RT_RCDATA);
    if (!res) {
        error = L"\x5185\x5D4C DLL \x7F3A\x5931";
        return {};
    }
    DWORD size = SizeofResource(nullptr, res);
    HGLOBAL handle = LoadResource(nullptr, res);
    void* data = handle ? LockResource(handle) : nullptr;
    if (!data || size == 0) {
        error = L"\x5185\x5D4C DLL \x4E3A\x7A7A";
        return {};
    }
    wchar_t temp[MAX_PATH];
    if (GetTempPathW(MAX_PATH, temp) == 0) {
        error = L"\x65E0\x6CD5\x5B9A\x4F4D TEMP";
        return {};
    }
    std::wstring path = std::wstring(temp) + L"naven-" + std::to_wstring(GetCurrentProcessId())
                        + L"-" + std::to_wstring(GetTickCount64()) + L".dll";
    HANDLE file = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        error = L"\x65E0\x6CD5\x5199\x5165 TEMP";
        return {};
    }
    DWORD written = 0;
    BOOL ok = WriteFile(file, data, size, &written, nullptr);
    CloseHandle(file);
    if (!ok || written != size) {
        DeleteFileW(path.c_str());
        error = L"\x5199\x5165 DLL \x5931\x8D25";
        return {};
    }
    return path;
}

std::wstring sidecarDllPath() {
    wchar_t exe[MAX_PATH];
    GetModuleFileNameW(nullptr, exe, MAX_PATH);
    std::wstring path(exe);
    size_t slash = path.find_last_of(L'\\');
    return (slash == std::wstring::npos ? std::wstring(L".") : path.substr(0, slash)) + L"\\Naven.dll";
}

bool fileExists(const std::wstring& p) {
    DWORD attr = GetFileAttributesW(p.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
}

// ---------------------------------------------------------------------------
// injection
// ---------------------------------------------------------------------------
void postProgress(float value, const wchar_t* detail = L"") {
    auto* payload = new std::pair<float, std::wstring>(value, detail ? detail : L"");
    PostMessageW(g.hwnd, WM_APP + 1, 0, (LPARAM)payload);
}

bool injectRemote(DWORD pid, const std::wstring& dll) {
    HANDLE process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
                                 PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
                                 FALSE, pid);
    if (!process) {
        postProgress(0.0f, L"\x9700\x8981\x7BA1\x7406\x5458\x6743\x9650");
        return false;
    }
    postProgress(0.12f);

    size_t bytes = (dll.size() + 1) * sizeof(wchar_t);
    void* remote = VirtualAllocEx(process, nullptr, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote) {
        postProgress(0.0f, L"VirtualAllocEx \x5931\x8D25");
        CloseHandle(process);
        return false;
    }
    SIZE_T written = 0;
    if (!WriteProcessMemory(process, remote, dll.c_str(), bytes, &written)) {
        postProgress(0.0f, L"WriteProcessMemory \x5931\x8D25");
        CloseHandle(process);
        return false;
    }
    postProgress(0.28f);

    HMODULE kernel = GetModuleHandleW(L"kernel32.dll");
    auto loadLibrary = (LPTHREAD_START_ROUTINE)GetProcAddress(kernel, "LoadLibraryW");
    HANDLE thread = CreateRemoteThread(process, nullptr, 0, loadLibrary, remote, 0, nullptr);
    if (!thread) {
        postProgress(0.0f, L"CreateRemoteThread \x5931\x8D25");
        CloseHandle(process);
        return false;
    }
    postProgress(0.42f);

    WaitForSingleObject(thread, 15000);
    CloseHandle(thread);
    postProgress(0.55f);

    wchar_t temp[MAX_PATH];
    GetTempPathW(MAX_PATH, temp);
    std::wstring logPath = std::wstring(temp) + L"naven.log";
    DWORD deadline = GetTickCount() + 45000;
    float creep = 0.55f;

    while (GetTickCount() < deadline) {
        Sleep(350);
        if (!fileExists(logPath)) continue;
        HANDLE f = CreateFileW(logPath.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f == INVALID_HANDLE_VALUE) continue;
        char buf[8192] = {};
        DWORD got = 0;
        DWORD size = GetFileSize(f, nullptr);
        DWORD start = size > sizeof(buf) - 1 ? size - (sizeof(buf) - 1) : 0;
        SetFilePointer(f, (LONG)start, nullptr, FILE_BEGIN);
        ReadFile(f, buf, sizeof(buf) - 1, &got, nullptr);
        CloseHandle(f);

        std::string text(buf, got);
        if (text.find("bootstrap finished with code 0") != std::string::npos) {
            postProgress(1.0f);
            CloseHandle(process);
            return true;
        }
        if (creep < 0.94f) {
            creep += 0.01f;
            postProgress(creep);
        }
    }
    postProgress(0.0f, L"\x8D85\x65F6\xFF0C\x67E5\x770B %TEMP%\\naven.log");
    CloseHandle(process);
    return false;
}

void refreshAsync();

void beginInjection() {
    if (g.stage == Stage::Running) return;
    if (g.pid == 0) return;

    std::wstring error;
    std::wstring dll = embeddedDllPath(error);
    if (dll.empty()) {
        std::wstring sidecar = sidecarDllPath();
        if (fileExists(sidecar)) {
            dll = sidecar;
        } else {
            g.stage = Stage::Failed;
            g.progress = 0.0f;
            g.detail = error;
            InvalidateRect(g.hwnd, nullptr, FALSE);
            return;
        }
    }
    g.stage = Stage::Running;
    g.progress = 0.02f;
    g.detail.clear();
    InvalidateRect(g.hwnd, nullptr, FALSE);
    std::thread([pid = g.pid, dll] { injectRemote(pid, dll); }).detach();
}

void refreshAsync() {
    if (g_refreshing.exchange(true)) return;
    HWND hwnd = g.hwnd;
    std::thread([hwnd] {
        auto procs = queryJavaProcesses();
        std::sort(procs.begin(), procs.end(),
                  [](const ProcInfo& a, const ProcInfo& b) { return a.score > b.score; });
        auto* payload = new std::vector<ProcInfo>(std::move(procs));
        g_refreshing.store(false);
        PostMessageW(hwnd, WM_APP + 2, 0, (LPARAM)payload);
    }).detach();
}

// ---------------------------------------------------------------------------
// rendering
// ---------------------------------------------------------------------------
RECT buttonRect() {
    return RECT{ kWidth / 2 - 54, 244, kWidth / 2 + 54, 274 };
}

RECT barRect() {
    return RECT{ kWidth / 2 - 104, 255, kWidth / 2 + 104, 261 };
}

RECT closeRect() {
    return RECT{ kWidth - 42, 12, kWidth - 18, 36 };
}

void addRoundedRect(GraphicsPath& path, const RectF& r, float radius) {
    float d = radius * 2.0f;
    if (d > r.Width) d = r.Width;
    if (d > r.Height) d = r.Height;
    path.AddArc(r.X, r.Y, d, d, 180.0f, 90.0f);
    path.AddArc(r.GetRight() - d, r.Y, d, d, 270.0f, 90.0f);
    path.AddArc(r.GetRight() - d, r.GetBottom() - d, d, d, 0.0f, 90.0f);
    path.AddArc(r.X, r.GetBottom() - d, d, d, 90.0f, 90.0f);
    path.CloseFigure();
}

bool fontAvailable(const wchar_t* name) {
    InstalledFontCollection fonts;
    int count = fonts.GetFamilyCount();
    if (count <= 0) return false;
    std::vector<FontFamily> families((size_t)count);
    int found = 0;
    fonts.GetFamilies(count, families.data(), &found);
    for (int i = 0; i < found; ++i) {
        WCHAR buf[LF_FACESIZE] = {};
        families[(size_t)i].GetFamilyName(buf);
        if (_wcsicmp(buf, name) == 0) return true;
    }
    return false;
}

const wchar_t* wordmarkFamily() {
    static const wchar_t* cached = nullptr;
    if (!cached) cached = fontAvailable(L"Bahnschrift") ? L"Bahnschrift" : L"Segoe UI";
    return cached;
}

void drawText(Graphics& g, const std::wstring& s, const RectF& box, float sizePx,
              INT style, ARGB color, const wchar_t* familyName = nullptr) {
    FontFamily family(familyName ? familyName : L"Segoe UI");
    Font font(&family, sizePx, style, UnitPixel);
    SolidBrush brush{Color(color)};
    StringFormat format;
    format.SetAlignment(StringAlignmentCenter);
    format.SetLineAlignment(StringAlignmentCenter);
    g.DrawString(s.c_str(), (INT)s.size(), &font, box, &format, &brush);
}

void render() {
    if (!g_memDc) return;

    // clear to fully transparent
    memset(g_bits, 0, (size_t)kWidth * kHeight * 4);

    Graphics graphics(g_memDc);
    graphics.SetSmoothingMode(SmoothingModeAntiAlias);
    graphics.SetPixelOffsetMode(PixelOffsetModeHalf);
    graphics.SetTextRenderingHint(TextRenderingHintAntiAliasGridFit);

    // body: vertical gradient + hairline border
    {
        GraphicsPath path;
        RectF body(0.0f, 0.0f, (float)kWidth, (float)kHeight);
        addRoundedRect(path, body, (float)kRadius);
        LinearGradientBrush brush(PointF(0.0f, 0.0f), PointF(0.0f, (REAL)kHeight),
                                  Color(kBgTop), Color(kBgBottom));
        graphics.FillPath(&brush, &path);
        Pen pen{Color(kBorder), 1.0f};
        graphics.DrawPath(&pen, &path);
    }

    bool showsBar = g.stage == Stage::Running || g.stage == Stage::Done || g.stage == Stage::Failed;
    bool showsButton = !showsBar && g.pid != 0;

    // close glyph
    {
        RectF box((REAL)(kWidth - 38), 8.0f, 22.0f, 22.0f);
        FontFamily family(L"Segoe UI");
        Font font(&family, 13.0f, FontStyleRegular, UnitPixel);
        SolidBrush brush{Color(g.hotClose ? 0xFF96989E : kFaint)};
        StringFormat fmt;
        fmt.SetAlignment(StringAlignmentCenter);
        fmt.SetLineAlignment(StringAlignmentCenter);
        graphics.DrawString(L"\x00D7", 1, &font, box, &fmt, &brush);
    }

    // wordmark
    float logoCenterY = (showsBar || showsButton) ? 150.0f : (float)kHeight / 2.0f;
    {
        RectF box(0.0f, logoCenterY - 30.0f, (float)kWidth, 60.0f);
        drawText(graphics, L"NAVEN", box, 44.0f, FontStyleBold, kText, wordmarkFamily());
    }

    if (showsBar) {
        RECT bar = barRect();
        RectF track((REAL)bar.left, (REAL)bar.top,
                    (REAL)(bar.right - bar.left), (REAL)(bar.bottom - bar.top));
        GraphicsPath trackPath;
        addRoundedRect(trackPath, track, track.Height / 2.0f);
        SolidBrush trackBrush{Color(kTrack)};
        graphics.FillPath(&trackBrush, &trackPath);

        float fillW = track.Width * (g.progress < 0 ? 0.0f : g.progress);
        if (fillW > 4.0f) {
            RectF fill(track.X, track.Y, fillW, track.Height);
            GraphicsPath fillPath;
            addRoundedRect(fillPath, fill, fill.Height / 2.0f);
            SolidBrush fillBrush{Color(g.stage == Stage::Failed ? kRed : kBar)};
            graphics.FillPath(&fillBrush, &fillPath);
        }
        if (g.stage == Stage::Failed && !g.detail.empty()) {
            RectF err(16.0f, 300.0f, (REAL)kWidth - 32.0f, 24.0f);
            drawText(graphics, g.detail, err, 13.0f, FontStyleRegular, kRed);
        }
    } else if (showsButton) {
        RECT button = buttonRect();
        RectF box((REAL)button.left, (REAL)button.top,
                  (REAL)(button.right - button.left), (REAL)(button.bottom - button.top));
        GraphicsPath path;
        addRoundedRect(path, box, 6.0f);
        SolidBrush fill{Color(g.hotButton ? kGreenHot : kGreen)};
        graphics.FillPath(&fill, &path);
        RectF label(box.X, box.Y + 1.0f, box.Width, box.Height);
        drawText(graphics, L"INJECT", label, 12.0f, FontStyleBold, kButtonText);
    }

    // push the buffer to the screen with per-pixel alpha
    RECT window;
    GetWindowRect(g.hwnd, &window);
    POINT dst{ window.left, window.top };
    SIZE size{ kWidth, kHeight };
    POINT src{ 0, 0 };
    BLENDFUNCTION blend{ AC_SRC_OVER, 0, 255, AC_SRC_ALPHA };
    HDC screen = GetDC(nullptr);
    UpdateLayeredWindow(g.hwnd, screen, &dst, &size, g_memDc, &src, 0, &blend, ULW_ALPHA);
    ReleaseDC(nullptr, screen);
}

bool hit(const RECT& r, int x, int y) {
    return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
}

// ---------------------------------------------------------------------------
// window proc
// ---------------------------------------------------------------------------
LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_CREATE:
        g.hwnd = hwnd;
        SetTimer(hwnd, kTimerRefresh, 1500, nullptr);
        refreshAsync();
        return 0;

    case WM_TIMER:
        if (wp == kTimerRefresh && g.stage != Stage::Running) {
            refreshAsync();
        } else if (wp == kTimerQuit) {
            KillTimer(hwnd, kTimerQuit);
            PostQuitMessage(0);
        }
        return 0;

    case WM_APP + 2: {
        auto* procs = (std::vector<ProcInfo>*)lp;
        g.pid = procs->empty() ? 0 : procs->front().pid;
        delete procs;
        render();
        return 0;
    }

    case WM_APP + 1: {
        auto* payload = (std::pair<float, std::wstring>*)lp;
        g.progress = payload->first;
        g.detail = payload->second;
        if (g.progress >= 1.0f) {
            g.stage = Stage::Done;
            // Job done: leave the finished bar on screen briefly, then close.
            SetTimer(hwnd, kTimerQuit, 900, nullptr);
        } else if (g.progress <= 0.0f && g.stage == Stage::Running) {
            g.stage = Stage::Failed;
        }
        delete payload;
        render();
        return 0;
    }

    case WM_MOUSEMOVE: {
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        bool hb = g.stage == Stage::Idle && g.pid != 0 && hit(buttonRect(), x, y);
        bool hc = hit(closeRect(), x, y);
        if (hb != g.hotButton || hc != g.hotClose) {
            g.hotButton = hb;
            g.hotClose = hc;
            TRACKMOUSEEVENT tme{ sizeof(tme), TME_LEAVE, hwnd, 0 };
            TrackMouseEvent(&tme);
            SetCursor(LoadCursor(nullptr, (hb || hc) ? IDC_HAND : IDC_ARROW));
            render();
        }
        return 0;
    }
    case WM_MOUSELEAVE:
        g.hotButton = false;
        g.hotClose = false;
        render();
        return 0;

    case WM_LBUTTONDOWN: {
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        if (hit(closeRect(), x, y)) {
            PostQuitMessage(0);
        } else if (g.stage == Stage::Idle && g.pid != 0 && hit(buttonRect(), x, y)) {
            beginInjection();
        }
        return 0;
    }

    case WM_NCHITTEST: {
        POINT pt{ GET_X_LPARAM(lp), GET_Y_LPARAM(lp) };
        ScreenToClient(hwnd, &pt);
        if ((g.stage == Stage::Idle && g.pid != 0 && hit(buttonRect(), pt.x, pt.y)) ||
            hit(closeRect(), pt.x, pt.y)) return HTCLIENT;
        return HTCAPTION; // drag the frameless window anywhere else
    }

    case WM_ERASEBKGND:
        return 1;

    case WM_PAINT: {
        // Layered windows are drawn from the DIB; just validate.
        PAINTSTRUCT ps;
        BeginPaint(hwnd, &ps);
        EndPaint(hwnd, &ps);
        return 0;
    }

    case WM_KEYDOWN:
        if (wp == VK_RETURN) beginInjection();
        if (wp == VK_ESCAPE) PostQuitMessage(0);
        return 0;

    case WM_DESTROY:
        KillTimer(hwnd, kTimerRefresh);
        KillTimer(hwnd, kTimerQuit);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

bool createBackBuffer() {
    BITMAPINFO info{};
    info.bmiHeader.biSize = sizeof(info.bmiHeader);
    info.bmiHeader.biWidth = kWidth;
    info.bmiHeader.biHeight = -kHeight; // top-down
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;

    HDC screen = GetDC(nullptr);
    g_dib = CreateDIBSection(screen, &info, DIB_RGB_COLORS, &g_bits, nullptr, 0);
    ReleaseDC(nullptr, screen);
    if (!g_dib) return false;
    g_memDc = CreateCompatibleDC(nullptr);
    g_oldBitmap = (HBITMAP)SelectObject(g_memDc, g_dib);
    return true;
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    GdiplusStartupInput input;
    if (GdiplusStartup(&g_gdiplusToken, &input, nullptr) == Ok) g_gdiplus = true;

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = wndProc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.hbrBackground = nullptr;
    wc.lpszClassName = kClass;
    RegisterClassExW(&wc);

    int screenW = GetSystemMetrics(SM_CXSCREEN);
    int screenH = GetSystemMetrics(SM_CYSCREEN);
    HWND hwnd = CreateWindowExW(WS_EX_LAYERED, kClass, L"Naven Injector", WS_POPUP,
                                (screenW - kWidth) / 2, (screenH - kHeight) / 2,
                                kWidth, kHeight, nullptr, nullptr, instance, nullptr);
    if (!hwnd) return 1;
    if (!createBackBuffer()) return 1;

    // First frame before showing: the layered window takes its pixels from this call.
    ShowWindow(hwnd, show);
    render();

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    if (g_memDc) {
        SelectObject(g_memDc, g_oldBitmap);
        DeleteDC(g_memDc);
    }
    if (g_dib) DeleteObject(g_dib);
    if (g_gdiplus) GdiplusShutdown(g_gdiplusToken);
    return 0;
}