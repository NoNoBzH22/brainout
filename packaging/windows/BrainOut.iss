; One file installer of BrainOut for Windows, built with Inno Setup from the portable folder
; made by packaging/build_portable.sh:
;   iscc /DSource=<portable BrainOut folder> /DOutput=<output folder> /DVersion=<version> BrainOut.iss
; Installed for the current user only, in a folder the game can write its settings to,
; so no administrator rights are needed.

#ifndef Version
  #define Version "1.0"
#endif

[Setup]
AppId={{7B1E2C4A-5D3F-4E8B-9A61-B7A0C2D4E5F6}
AppName=BrainOut
AppVersion={#Version}
AppPublisher=NoNoBzH22
DefaultDirName={localappdata}\BrainOut
DefaultGroupName=BrainOut
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir={#Output}
OutputBaseFilename=BrainOut-Setup
SetupIconFile={#Source}\game\icon.ico
UninstallDisplayIcon={app}\BrainOut.exe
Compression=lzma2/fast
SolidCompression=yes
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
WizardStyle=modern
CloseApplications=force

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; game data of the previous version, the player's settings next to it are kept
Type: filesandordirs; Name: "{app}\game\packages"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#Source}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\BrainOut"; Filename: "{app}\BrainOut.exe"
Name: "{group}\Desinstaller BrainOut"; Filename: "{uninstallexe}"
Name: "{userdesktop}\BrainOut"; Filename: "{app}\BrainOut.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\BrainOut.exe"; Description: "{cm:LaunchProgram,BrainOut}"; Flags: nowait postinstall skipifsilent
; updates from the game install silently, then start it again
Filename: "{app}\BrainOut.exe"; Flags: nowait; Check: WizardSilent
