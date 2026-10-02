# Raise a GitHub Windows runner's screen from 1024x768 to 1920x1080. At 1024x768 the sidebars leave
# the image a strip 30 pixels wide. ChangeDisplaySettings is the only way in on a machine with no
# display settings UI. Called by launch.yml and package.yml (shell: pwsh).
Add-Type @"
using System; using System.Runtime.InteropServices;
public class Res {
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
  public struct DEVMODE {
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
    public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
    public int dmFields, dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
    public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
    public short dmLogPixels;
    public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency,
               dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2,
               dmPanningWidth, dmPanningHeight;
  }
  [DllImport("user32.dll")] public static extern int EnumDisplaySettings(string d, int m, ref DEVMODE dm);
  [DllImport("user32.dll")] public static extern int ChangeDisplaySettings(ref DEVMODE dm, int f);
}
"@
$dm = New-Object Res+DEVMODE
$dm.dmSize = [Runtime.InteropServices.Marshal]::SizeOf($dm)
[Res]::EnumDisplaySettings($null, -1, [ref]$dm) | Out-Null
$dm.dmPelsWidth = 1920; $dm.dmPelsHeight = 1080; $dm.dmFields = 0x180000
"ChangeDisplaySettings returned $([Res]::ChangeDisplaySettings([ref]$dm, 0)) (0 is success)"
