<#
.SYNOPSIS
    Injects Naven.dll into a running Minecraft (NeoForge) JVM.

.DESCRIPTION
    Classic remote LoadLibraryA injection:
        OpenProcess -> VirtualAllocEx -> WriteProcessMemory ->
        CreateRemoteThread(kernel32!LoadLibraryA)

    The DLL bootstraps itself in the target process (JavaVM lookup, agent-jar
    extraction, instrument.dll!Agent_OnAttach, game class loader lookup) and
    writes its progress to %TEMP%\naven.log. Check that file after injecting.

    The injector needs the same or higher privileges as the game process.
    Start PowerShell as administrator if the game runs elevated.

    Candidate selection: without -Pid every javaw/java process is scored by its
    command line (1.21.10 and NeoForge markers win, Gradle daemons lose). If the
    best candidate does not look like a Minecraft client, the script refuses to
    inject unless -Force or an explicit -Pid is given.

.PARAMETER ProcessId
    Target process id. Also accepted as -Pid.

.PARAMETER Name
    When -Pid is omitted, only javaw/java processes whose process name, image
    path, window title or command line contains this string are considered.

.PARAMETER Dll
    DLL to inject. Defaults to <repo>\build\native\Naven.dll.

.PARAMETER WaitLogSeconds
    After injecting, wait up to this many seconds for %TEMP%\naven.log to be
    rewritten, then print its tail (default: 2s).

.PARAMETER Force
    Allow auto-detection to inject into a javaw/java process that does not look
    like a Minecraft client. Has no effect with -Pid.

.PARAMETER DryRun
    Print the selected process and candidate scores without injecting.

.EXAMPLE
    .\inject.ps1

.EXAMPLE
    .\inject.ps1 -Pid 12345

.EXAMPLE
    .\inject.ps1 -Name Minecraft -WaitLogSeconds 10
#>
[CmdletBinding(DefaultParameterSetName = 'Auto')]
param(
    [Parameter(ParameterSetName = 'Pid', Mandatory = $true)]
    [Alias('Pid')]
    [int]$ProcessId,

    [Parameter(ParameterSetName = 'Auto')]
    [string]$Name,

    [string]$Dll,

    [ValidateRange(0, 120)]
    [int]$WaitLogSeconds = 2,

    [switch]$Force,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

trap {
    Write-Host "[inject] ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

function Write-Step([string]$Message) {
    Write-Host "[inject] $Message"
}

# ---------------------------------------------------------------------------
# Win32 bindings. Kept ASCII-only so Windows PowerShell 5.1 parses this file
# correctly without a BOM.
# ---------------------------------------------------------------------------
if (-not ('NavenRemote' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class NavenRemote
{
    public const uint PROCESS_CREATE_THREAD     = 0x0002;
    public const uint PROCESS_VM_OPERATION      = 0x0008;
    public const uint PROCESS_VM_READ           = 0x0010;
    public const uint PROCESS_VM_WRITE          = 0x0020;
    public const uint PROCESS_QUERY_INFORMATION = 0x0400;

    public const uint MEM_COMMIT  = 0x1000;
    public const uint MEM_RESERVE = 0x2000;
    public const uint MEM_RELEASE = 0x8000;
    public const uint PAGE_READWRITE = 0x04;

    public const uint WAIT_OBJECT_0 = 0;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inheritHandle, int processId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr VirtualAllocEx(IntPtr process, IntPtr address,
        UIntPtr size, uint allocationType, uint protect);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool VirtualFreeEx(IntPtr process, IntPtr address,
        UIntPtr size, uint freeType);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool WriteProcessMemory(IntPtr process, IntPtr address,
        byte[] buffer, UIntPtr size, out UIntPtr written);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateRemoteThread(IntPtr process, IntPtr threadAttributes,
        uint stackSize, IntPtr startAddress, IntPtr parameter, uint creationFlags, out uint threadId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Ansi)]
    private static extern IntPtr GetModuleHandleA(string moduleName);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Ansi)]
    private static extern IntPtr GetProcAddress(IntPtr module, string procName);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool IsWow64Process(IntPtr process, out bool wow64);

    public static IntPtr OpenTarget(int processId)
    {
        uint access = PROCESS_CREATE_THREAD | PROCESS_VM_OPERATION | PROCESS_VM_WRITE
                    | PROCESS_VM_READ | PROCESS_QUERY_INFORMATION;
        return OpenProcess(access, false, processId);
    }

    public static IntPtr Allocate(IntPtr process, int size)
    {
        return VirtualAllocEx(process, IntPtr.Zero, new UIntPtr((uint)size),
            MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    }

    public static void Release(IntPtr process, IntPtr address)
    {
        VirtualFreeEx(process, address, UIntPtr.Zero, MEM_RELEASE);
    }

    public static bool Write(IntPtr process, IntPtr address, byte[] buffer, out int written)
    {
        UIntPtr count;
        bool ok = WriteProcessMemory(process, address, buffer,
            new UIntPtr((uint)buffer.Length), out count);
        written = (int)count.ToUInt32();
        return ok;
    }

    public static IntPtr StartThread(IntPtr process, IntPtr startAddress, IntPtr parameter,
        out uint threadId)
    {
        return CreateRemoteThread(process, IntPtr.Zero, 0, startAddress, parameter, 0,
            out threadId);
    }

    public static uint Wait(IntPtr thread, uint milliseconds)
    {
        return WaitForSingleObject(thread, milliseconds);
    }

    public static void Close(IntPtr handle)
    {
        CloseHandle(handle);
    }

    public static IntPtr Kernel32()
    {
        return GetModuleHandleA("kernel32.dll");
    }

    public static IntPtr ProcAddress(IntPtr module, string name)
    {
        return GetProcAddress(module, name);
    }

    public static bool IsWow64(IntPtr process)
    {
        bool wow64;
        if (!IsWow64Process(process, out wow64))
        {
            throw new InvalidOperationException(
                "IsWow64Process failed: Win32 error " + Marshal.GetLastWin32Error());
        }
        return wow64;
    }
}
'@
}

# ---------------------------------------------------------------------------
# Process discovery. A machine can run several JVMs (Gradle daemons, other
# Minecraft instances, IDE workers), so candidates are scored instead of
# blindly taking the first javaw/java process. -Pid always wins.
# ---------------------------------------------------------------------------
function Get-ProcessInfo {
    param([System.Diagnostics.Process]$Process)

    $path = ''
    try { $path = $Process.Path } catch { $path = '' }

    $title = ''
    try { $title = $Process.MainWindowTitle } catch { $title = '' }

    $commandLine = ''
    try {
        $cim = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $($Process.Id)" -ErrorAction Stop
        if ($cim) { $commandLine = [string]$cim.CommandLine }
    }
    catch {
        $commandLine = ''
    }

    # $null means "could not determine" (access denied / cross-architecture),
    # as opposed to $false which means jvm.dll is definitely not loaded.
    $hasJvm = $null
    try {
        $hasJvm = [bool](@($Process.Modules | Where-Object { $_.ModuleName -eq 'jvm.dll' }).Count)
    }
    catch {
        $hasJvm = $null
    }

    $score = 0
    if ($hasJvm -eq $true) { $score += 5 }
    if ($Process.ProcessName -eq 'javaw') { $score += 40 }
    if ($title) { $score += 20 }
    # Naven only supports 1.21.10 NeoForge clients; prefer them strongly.
    if ($commandLine -match '(?i)1[.]21[.]10') { $score += 300 }
    if ($commandLine -match '(?i)neoforge|net[.]neoforged|forgeclient') { $score += 200 }
    if ($commandLine -match '(?i)net[.]minecraft[.]client|minecraft|BootstrapLauncher|devlaunch') { $score += 100 }
    # Legacy Forge dev launchers and build daemons are definitely not our target.
    if ($commandLine -match '(?i)1[.]12[.]2|GradleStart|launchwrapper') { $score -= 250 }
    if ($commandLine -match '(?i)GradleDaemon|gradle-launcher|KotlinCompileDaemon|kotlin-daemon|GradleWorkerMain') { $score -= 300 }

    $snippet = $commandLine
    if ($snippet.Length -gt 110) { $snippet = $snippet.Substring(0, 110) + '...' }

    [pscustomobject]@{
        Id          = $Process.Id
        Name        = $Process.ProcessName
        Path        = $path
        Title       = $title
        CommandLine = $commandLine
        Snippet     = $snippet
        HasJvm      = $hasJvm
        Score       = $score
    }
}

function Invoke-NavenInject {
    param(
        [int]$TargetId,
        [string]$DllPath
    )

    $bytes = [System.Text.Encoding]::ASCII.GetBytes($DllPath)

    $handle = [NavenRemote]::OpenTarget($TargetId)
    if ($handle -eq [IntPtr]::Zero) {
        throw ("OpenProcess($TargetId) failed: Win32 error " +
            [Runtime.InteropServices.Marshal]::GetLastWin32Error() +
            ". Run PowerShell as administrator if the game runs elevated.")
    }

    $remote = [IntPtr]::Zero
    $thread = [IntPtr]::Zero
    try {
        if ([NavenRemote]::IsWow64($handle)) {
            throw 'Target is a 32-bit (WOW64) process; Naven.dll is x64-only.'
        }

        # +1 byte: the buffer is zero-initialised, so LoadLibraryA gets a
        # NUL-terminated path.
        $remote = [NavenRemote]::Allocate($handle, $bytes.Length + 1)
        if ($remote -eq [IntPtr]::Zero) {
            throw ("VirtualAllocEx($($bytes.Length + 1)) failed: Win32 error " +
                [Runtime.InteropServices.Marshal]::GetLastWin32Error())
        }

        $written = 0
        $ok = [NavenRemote]::Write($handle, $remote, $bytes, [ref]$written)
        if (-not $ok -or $written -ne $bytes.Length) {
            throw ("WriteProcessMemory failed: Win32 error " +
                [Runtime.InteropServices.Marshal]::GetLastWin32Error())
        }

        $kernel32 = [NavenRemote]::Kernel32()
        $loadLibrary = [NavenRemote]::ProcAddress($kernel32, 'LoadLibraryA')
        if ($loadLibrary -eq [IntPtr]::Zero) {
            throw 'GetProcAddress(kernel32!LoadLibraryA) failed.'
        }

        $threadId = [uint32]0
        $thread = [NavenRemote]::StartThread($handle, $loadLibrary, $remote, [ref]$threadId)
        if ($thread -eq [IntPtr]::Zero) {
            throw ("CreateRemoteThread failed: Win32 error " +
                [Runtime.InteropServices.Marshal]::GetLastWin32Error())
        }
        Write-Step "remote thread $threadId started"

        $wait = [NavenRemote]::Wait($thread, 10000)
        if ($wait -ne [NavenRemote]::WAIT_OBJECT_0) {
            throw "remote LoadLibraryA did not finish within 10s (wait result $wait)"
        }
        Write-Step 'remote LoadLibraryA returned'
    }
    finally {
        if ($thread -ne [IntPtr]::Zero) { [NavenRemote]::Close($thread) }
        if ($remote -ne [IntPtr]::Zero) { [NavenRemote]::Release($handle, $remote) }
        [NavenRemote]::Close($handle)
    }
}

function Read-LogTail {
    param([string]$Path, [int]$Lines)

    # Share the file with writers: the DLL may be appending while we read, and
    # holding it exclusively would make the DLL fall back to its pid log.
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::ReadWrite)
    try {
        $reader = New-Object System.IO.StreamReader($stream)
        $text = $reader.ReadToEnd()
    }
    finally {
        $stream.Dispose()
    }
    ($text -split "`r?`n") | Select-Object -Last $Lines
}

# ---------------------------------------------------------------------------
# Resolve the DLL.
# ---------------------------------------------------------------------------
if (-not $Dll) {
    $Dll = Join-Path (Split-Path -Parent $PSScriptRoot) 'build\native\Naven.dll'
}
if (-not (Test-Path -LiteralPath $Dll -PathType Leaf)) {
    throw "DLL not found: $Dll. Build it first with native\build.bat."
}
$Dll = (Resolve-Path -LiteralPath $Dll).Path

$nonAscii = $false
foreach ($ch in $Dll.ToCharArray()) {
    $code = [int]$ch
    if ($code -lt 32 -or $code -gt 126) { $nonAscii = $true }
}
if ($nonAscii) {
    throw "DLL path must be ASCII (LoadLibraryA): $Dll"
}

# ---------------------------------------------------------------------------
# Pick the target process.
# ---------------------------------------------------------------------------
if ($PSCmdlet.ParameterSetName -eq 'Pid') {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $process) { throw "No process with id $ProcessId." }
    $target = Get-ProcessInfo -Process $process
    if ($target.HasJvm -eq $false) {
        Write-Step "WARNING: pid $ProcessId has no jvm.dll loaded; injection will fail"
    }
}
else {
    $all = @(Get-Process -Name javaw, java -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ProcessInfo -Process $_ })

    if ($all.Count -eq 0) {
        throw 'No javaw/java process found. Start Minecraft, or pass -Pid explicitly.'
    }

    $candidates = $all
    if ($Name) {
        $candidates = @($all | Where-Object {
            $_.Name -like "*$Name*" -or $_.Path -like "*$Name*" -or
            $_.Title -like "*$Name*" -or $_.CommandLine -like "*$Name*"
        })
        if ($candidates.Count -eq 0) {
            throw "No javaw/java process matched -Name '$Name'."
        }
    }

    Write-Step 'candidates (pid / name / score / jvm / command line):'
    $candidates | Sort-Object -Property Score -Descending | ForEach-Object {
        $jvmText = switch ($_.HasJvm) {
            $true   { 'jvm' }
            $false  { 'no-jvm' }
            default { 'jvm?' }
        }
        Write-Host ("  {0,-7} {1,-6} {2,4}  {3,-7} {4}" -f $_.Id, $_.Name, $_.Score, $jvmText, $_.Snippet)
    }

    $target = $candidates | Sort-Object -Property @{ Expression = 'Score'; Descending = $true }, Id |
        Select-Object -First 1

    if ($target.HasJvm -eq $false) {
        throw ("pid $($target.Id) has no jvm.dll loaded - it is not a JVM. " +
            'Pass -Pid with the actual game process.')
    }

    # A Minecraft client always carries launcher markers in its command line.
    # Without them, injecting into a random JVM is more likely a mistake than a
    # win, so require -Force.
    if ($target.Score -lt 100 -and -not $Force) {
        throw ("pid $($target.Id) does not look like a Minecraft 1.21.10 client " +
            '(no net.minecraft / NeoForge / 1.21.10 marker in its command line). ' +
            'Use -Pid <pid> to target it explicitly, or -Force to override.')
    }

    if (@($candidates).Count -gt 1) {
        Write-Step "picked pid $($target.Id) out of $($candidates.Count) candidates; use -Pid to choose another"
    }
}

Write-Step ("target : pid {0} ({1})" -f $target.Id, $target.Path)
Write-Step ("dll    : {0}" -f $Dll)

if ($DryRun) {
    Write-Step 'dry run: nothing was injected'
    return
}

$logPath = Join-Path $env:TEMP 'naven.log'
$logBefore = $null
if (Test-Path -LiteralPath $logPath) {
    $logBefore = (Get-Item -LiteralPath $logPath).LastWriteTimeUtc
}

Invoke-NavenInject -TargetId $target.Id -DllPath $Dll
Write-Step 'injection call completed'

$process = Get-Process -Id $target.Id -ErrorAction SilentlyContinue
if (-not $process) {
    throw "pid $($target.Id) exited right after injection"
}

# Wait for the DLL to rewrite naven.log (CREATE_ALWAYS bumps LastWriteTime).
$deadline = (Get-Date).AddSeconds([Math]::Max($WaitLogSeconds, 0))
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 150
    if (Test-Path -LiteralPath $logPath) {
        $logNow = (Get-Item -LiteralPath $logPath).LastWriteTimeUtc
        if (($null -eq $logBefore) -or ($logNow -gt $logBefore)) { break }
    }
}
Start-Sleep -Milliseconds 400

if (Test-Path -LiteralPath $logPath) {
    Write-Host ''
    Write-Host "=== $logPath (tail) ==="
    Read-LogTail -Path $logPath -Lines 40 | ForEach-Object { Write-Host $_ }
}
else {
    Write-Step "log not found yet: $logPath (the DLL may have failed before logging)"
}

Write-Host ''
Write-Step 'done. If the game misbehaves, check the log above for the failing step.'
