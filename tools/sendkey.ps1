# Focuses the Minecraft window and sends a virtual key (default ESC).
param([int]$Vk = 0x1B, [int]$HoldMs = 60)

$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class Keys {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
}
'@

$proc = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -in @('java','javaw') -and $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
if (-not $proc) { throw "no Minecraft window" }
[Keys]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 400
[Keys]::keybd_event([byte]$Vk, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds $HoldMs
[Keys]::keybd_event([byte]$Vk, 0, 2, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 300
Write-Output ("sent vk=" + $Vk + " to " + $proc.MainWindowTitle)