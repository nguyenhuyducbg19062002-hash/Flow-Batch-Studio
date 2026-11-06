; Bộ cài Flow Batch Studio (Inno Setup 6.3+). Lưu file này dạng UTF-8 có BOM.
; Biên dịch: build_release.bat gọi ISCC.exe sau khi đã tạo build\image\FlowBatchStudio.

#define AppName "Flow Batch Studio"
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#define AppExe "FlowBatchStudio.exe"
; build_release.bat truyền /DImageDir (thư mục app image) và /DIconFile; giá trị dưới là mặc định khi biên dịch tay
#ifndef ImageDir
  #define ImageDir "C:\fbs-build\image\FlowBatchStudio"
#endif
#ifndef IconFile
  #define IconFile "C:\fbs-build\app.ico"
#endif

[Setup]
; GIỮ NGUYÊN AppId qua mọi phiên bản để bản mới cài đè lên bản cũ
AppId={{0296B0C5-F422-4DF6-9C31-0DEF968FFF51}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Flow Batch Studio
; Thư mục cài phải CHỈ GỒM KÝ TỰ ASCII: launcher của jpackage (JDK 17) không khởi động được JVM khi đường dẫn có
; chữ có dấu (ví dụ tên người dùng Windows "Nguyễn"). Vì vậy không dùng {localappdata}\Programs mà dùng C:\FlowBatchStudio.
DefaultDirName={sd}\FlowBatchStudio
; Không cần quyền admin (người dùng thường được tạo thư mục ở gốc ổ C:)
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
OutputBaseFilename=FlowBatchStudio-Setup-{#AppVersion}
#if FileExists(IconFile)
SetupIconFile={#IconFile}
#endif
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2/ultra64
SolidCompression=yes
; Windows 10 / 11 64-bit
MinVersion=10.0
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=force
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Tạo biểu tượng ngoài Desktop"

[Files]
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourcePath}\HUONG-DAN.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon
; Thư mục extension Chrome: người dùng "Load unpacked" chọn đúng thư mục này
Name: "{autoprograms}\{#AppName} - Extension Chrome"; Filename: "{app}\engine\flow_ext\extension"
Name: "{autoprograms}\{#AppName} - Hướng dẫn"; Filename: "{app}\HUONG-DAN.txt"

[Run]
Filename: "{app}\{#AppExe}"; Description: "Mở {#AppName}"; Flags: nowait postinstall skipifsilent
Filename: "{app}\HUONG-DAN.txt"; Description: "Xem hướng dẫn cài extension Chrome"; Flags: postinstall shellexec skipifsilent unchecked

[UninstallRun]
; Tắt cả cây tiến trình: Java → Python worker → Edge do Playwright mở
Filename: "{sys}\taskkill.exe"; Parameters: "/F /T /IM {#AppExe}"; Flags: runhidden; RunOnceId: "KillApp"

; Dữ liệu người dùng (%LOCALAPPDATA%\FlowBatchStudio: DB, profile tài khoản, file chờ duyệt) KHÔNG bị xóa khi gỡ cài đặt.

[Code]
function IsAsciiPath(const S: String): Boolean;
var
  I: Integer;
begin
  Result := True;
  for I := 1 to Length(S) do
    if Ord(S[I]) > 127 then
    begin
      Result := False;
      Exit;
    end;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if (CurPageID = wpSelectDir) and not IsAsciiPath(WizardDirValue) then
  begin
    MsgBox('Thư mục cài đặt không được chứa chữ có dấu (giới hạn của Java).' + #13#10 +
           'Hãy chọn thư mục khác, ví dụ C:\FlowBatchStudio.', mbError, MB_OK);
    Result := False;
  end;
end;
