# Clicks at window-relative coordinates of the Minecraft window.
param([int]$X = 268, [int]$Y = 428, [string]$Title = "Minecraft")

$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class Mouse {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, UIntPtr e);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
'@

$proc = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like ('*' + $Title + '*') } | Select-Object -First 1
if (-not $proc) { throw "no window matching '$Title'" }
[Mouse]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 500
$r = New-Object Mouse+RECT
[Mouse]::GetWindowRect($proc.MainWindowHandle, [ref]$r) | Out-Null
$px = $r.Left + $X
$py = $r.Top + $Y
[Mouse]::SetCursorPos($px, $py) | Out-Null
Start-Sleep -Milliseconds 200
[Mouse]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 80
[Mouse]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 500
Write-Output ("clicked " + $X + "," + $Y + " in " + $proc.MainWindowTitle)