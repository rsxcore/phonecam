#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <string>
#include <vector>
#include <fstream>
#include <thread>
#include <algorithm>
#include "../vcam/PhoneCamProtocol.h"
#pragma comment(lib,"ws2_32.lib")

static HINSTANCE instance;
static HWND window, addressEdit, codeEdit, statusLabel, connectButton, findButton, preview;
static HFONT font, titleFont;
static std::wstring directory, statusFile, configFile;
static HANDLE process = NULL, job = NULL, mapping = NULL;
static BYTE* mapped = NULL;
static bool inTray = false;
static std::vector<BYTE> frame(1280 * 720 * 4);
static bool haveFrame = false;
static constexpr UINT TrayMessage = WM_APP + 1, DiscoveryMessage = WM_APP + 2, RestoreMessage = WM_APP + 3;
static std::wstring wide(const std::string& s) {
    int n=MultiByteToWideChar(CP_UTF8,0,s.data(),(int)s.size(),NULL,0);
    std::wstring out(n,0); MultiByteToWideChar(CP_UTF8,0,s.data(),(int)s.size(),out.data(),n);return out;
}
static std::wstring text(HWND h) { int n=GetWindowTextLengthW(h);std::wstring s(n+1,0);GetWindowTextW(h,s.data(),n+1);s.resize(n);return s; }
static bool extract(int id,const std::wstring& path) {
    HRSRC resource=FindResourceW(instance,MAKEINTRESOURCEW(id),RT_RCDATA);
    if(!resource)return false;
    DWORD size=SizeofResource(instance,resource); const char* bytes=(const char*)LockResource(LoadResource(instance,resource));
    std::ifstream existing(path,std::ios::binary);
    if(existing){std::vector<char> contents((std::istreambuf_iterator<char>(existing)),{});if(contents.size()==size&&std::equal(contents.begin(),contents.end(),bytes))return true;}
    existing.close();std::ofstream out(path,std::ios::binary|std::ios::trunc);out.write(bytes,size);return out.good();
}
static bool prepare() {
    PWSTR local=NULL;if(FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData,0,NULL,&local)))return false;
    directory=std::wstring(local)+L"\\PhoneCam";CoTaskMemFree(local);
        CreateDirectoryW(directory.c_str(),NULL);
    configFile=directory+L"\\settings.ini";
    // Content-addressed payloads allow upgrading while another app still has
    // the previous virtual-camera DLL loaded. Never overwrite an in-use DLL.
    unsigned long long hash=14695981039346656037ULL;
    for(int id: {101,102}) {
        HRSRC resource=FindResourceW(instance,MAKEINTRESOURCEW(id),RT_RCDATA);
        if(!resource)return false;
        const BYTE* data=(const BYTE*)LockResource(LoadResource(instance,resource));
        for(DWORD i=0;i<SizeofResource(instance,resource);++i){hash^=data[i];hash*=1099511628211ULL;}
    }
    wchar_t suffix[48];swprintf_s(suffix,L"\\1.1-%016llx",hash);directory+=suffix;
    CreateDirectoryW(directory.c_str(),NULL);
    statusFile=directory+L"\\status.txt";
    return extract(101,directory+L"\\phonecam-server.exe")&&extract(102,directory+L"\\PhoneCam.dll");
}
static bool registration(bool remove=false) {
    HMODULE dll=LoadLibraryW((directory+L"\\PhoneCam.dll").c_str());if(!dll)return false;
    auto call=(HRESULT(STDAPICALLTYPE*)())GetProcAddress(dll,remove?"DllUnregisterServer":"DllRegisterServer");
    HRESULT result=call?call():E_FAIL;FreeLibrary(dll);return SUCCEEDED(result);
}
static void stop() {
    if(job){CloseHandle(job);job=NULL;}
    if(process){WaitForSingleObject(process,2000);CloseHandle(process);process=NULL;}
    SetWindowTextW(connectButton,L"Connect");SetWindowTextW(statusLabel,L"Camera disconnected");
    EnableWindow(addressEdit,TRUE);EnableWindow(codeEdit,TRUE);haveFrame=false;
}
static void start(bool test) {
    if(process)stop();
    std::wstring address=text(addressEdit),code=text(codeEdit);
    if(!test){
        if(address.empty()||address.find_first_of(L"\"\r\n\t ")!=std::wstring::npos){MessageBoxW(window,L"Enter the phone IP or the full address shown in the app.",L"PhoneCam",MB_ICONINFORMATION);return;}
        if(address.find(L"?code=")==std::wstring::npos){
            if(code.size()!=6||code.find_first_not_of(L"0123456789")!=std::wstring::npos){MessageBoxW(window,L"Enter the six-digit code shown on the phone screen.",L"PhoneCam",MB_ICONINFORMATION);return;}
            if(address.find(L"/")==std::wstring::npos)address+=L"/stream";
            else if(address.rfind(L"http://",0)==0 && address.find(L'/',7)==std::wstring::npos)address+=L"/stream";
            else if(address.back()==L'/')address+=L"stream";
            address+=L"?code="+code;
        }
        WritePrivateProfileStringW(L"PhoneCam",L"Address",text(addressEdit).c_str(),configFile.c_str());
    }
    if(!registration()){MessageBoxW(window,L"Could not register the camera for the current user.",L"PhoneCam",MB_ICONERROR);return;}
    DeleteFileW(statusFile.c_str());
    std::wstring exe=directory+L"\\phonecam-server.exe";
    std::wstring command=L"\""+exe+L"\" "+(test?L"--test":L"--url \""+address+L"\"")+L" --status \""+statusFile+L"\"";
    STARTUPINFOW startup={sizeof(startup)};PROCESS_INFORMATION child={};
    HANDLE childJob=CreateJobObjectW(NULL,NULL);JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits={};limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if(!childJob||!SetInformationJobObject(childJob,JobObjectExtendedLimitInformation,&limits,sizeof(limits))){if(childJob)CloseHandle(childJob);return;}
    if(!CreateProcessW(exe.c_str(),command.data(),NULL,NULL,FALSE,CREATE_NO_WINDOW|CREATE_SUSPENDED,NULL,directory.c_str(),&startup,&child)) {CloseHandle(childJob);MessageBoxW(window,L"Could not start the receiver.",L"PhoneCam",MB_ICONERROR);return;}
    if(!AssignProcessToJobObject(childJob,child.hProcess)){TerminateProcess(child.hProcess,1);CloseHandle(child.hProcess);CloseHandle(child.hThread);CloseHandle(childJob);return;}
    job=childJob;process=child.hProcess;ResumeThread(child.hThread);CloseHandle(child.hThread);
    SetWindowTextW(connectButton,L"Disconnect");EnableWindow(addressEdit,FALSE);EnableWindow(codeEdit,FALSE);
    SetWindowTextW(statusLabel,test?L"Test signal":L"Connecting to phone…");
}
static void discover() {
    EnableWindow(findButton,FALSE);SetWindowTextW(statusLabel,L"Searching for the phone on the local network…");
    HWND target=window;
    std::thread([target]{
        std::wstring result;SOCKET s=socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);
        if(s!=INVALID_SOCKET){
            BOOL yes=TRUE;setsockopt(s,SOL_SOCKET,SO_BROADCAST,(const char*)&yes,sizeof(yes));
            DWORD timeout=600;setsockopt(s,SOL_SOCKET,SO_RCVTIMEO,(const char*)&timeout,sizeof(timeout));
            sockaddr_in destination={};destination.sin_family=AF_INET;destination.sin_port=htons(5888);destination.sin_addr.s_addr=INADDR_BROADCAST;
            for(int i=0;i<4&&result.empty();++i){
                const char query[]="PHONECAM_DISCOVER_V1";sendto(s,query,sizeof(query)-1,0,(sockaddr*)&destination,sizeof(destination));
                char buffer[128]={};sockaddr_in sender={};int len=sizeof(sender);int n=recvfrom(s,buffer,127,0,(sockaddr*)&sender,&len);
                if(n>0&&std::string(buffer,n)=="PHONECAM_V1:8080"){
                    char ip[INET_ADDRSTRLEN];inet_ntop(AF_INET,&sender.sin_addr,ip,sizeof(ip));result=wide(ip)+L":8080";
                }
            }
            closesocket(s);
        }
        auto value=new std::wstring(result);if(!PostMessageW(target,DiscoveryMessage,0,(LPARAM)value))delete value;
    }).detach();
}
static void tray(bool add) {
    NOTIFYICONDATAW icon={sizeof(icon)};icon.hWnd=window;icon.uID=1;icon.uFlags=NIF_MESSAGE|NIF_ICON|NIF_TIP;icon.uCallbackMessage=TrayMessage;
    icon.hIcon=LoadIconW(instance,MAKEINTRESOURCEW(201));wcscpy_s(icon.szTip,L"PhoneCam — phone camera");
    if(Shell_NotifyIconW(add?NIM_ADD:NIM_DELETE,&icon)){inTray=add;if(add)ShowWindow(window,SW_HIDE);}
}
static void refreshPreview() {
    if(!mapped){mapping=OpenFileMappingW(FILE_MAP_READ,FALSE,PHONECAM_SHM_NAME);if(mapping)mapped=(BYTE*)MapViewOfFile(mapping,FILE_MAP_READ,0,0,PHONECAM_SHM_SIZE);if(!mapped&&mapping){CloseHandle(mapping);mapping=NULL;}}
    haveFrame=false;
    if(mapped){
        const auto h=(const PhoneCamHeader*)mapped;const UINT32 before=h->frameIndex;MemoryBarrier();
        if(before&&!(before&1)&&h->magic==PHONECAM_MAGIC&&h->width==1280&&h->height==720&&h->stride==5120&&h->activeBuffer<2&&(DWORD)(GetTickCount()-h->reserved[0])<2500){
            memcpy(frame.data(),mapped+PHONECAM_BUF_OFFSET(h->activeBuffer),frame.size());MemoryBarrier();haveFrame=h->frameIndex==before;
        }
    }
    InvalidateRect(preview,NULL,FALSE);
}
static LRESULT CALLBACK previewProc(HWND h,UINT msg,WPARAM w,LPARAM l) {
    if(msg==WM_PAINT){PAINTSTRUCT ps;HDC dc=BeginPaint(h,&ps);RECT r;GetClientRect(h,&r);FillRect(dc,&r,(HBRUSH)GetStockObject(BLACK_BRUSH));
        if(haveFrame){BITMAPINFO info={};info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);info.bmiHeader.biWidth=1280;info.bmiHeader.biHeight=-720;info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;
            SetStretchBltMode(dc,COLORONCOLOR);StretchDIBits(dc,0,0,r.right,r.bottom,0,0,1280,720,frame.data(),&info,DIB_RGB_COLORS,SRCCOPY);
        }else{SetBkMode(dc,TRANSPARENT);SetTextColor(dc,RGB(170,185,195));SelectObject(dc,font);DrawTextW(dc,L"Video will appear here",-1,&r,DT_CENTER|DT_VCENTER|DT_SINGLELINE);}
        EndPaint(h,&ps);return 0;
    }return DefWindowProcW(h,msg,w,l);
}
static HWND control(const wchar_t* cls,const wchar_t* label,DWORD style,int x,int y,int width,int height,int id=0){
    HWND h=CreateWindowExW((style&ES_AUTOHSCROLL)?WS_EX_CLIENTEDGE:0,cls,label,WS_CHILD|WS_VISIBLE|style,x,y,width,height,window,(HMENU)(INT_PTR)id,instance,NULL);SendMessageW(h,WM_SETFONT,(WPARAM)font,TRUE);return h;
}
static LRESULT CALLBACK procedure(HWND h,UINT msg,WPARAM w,LPARAM l) {
    switch(msg){
    case WM_CREATE:{
        window=h;font=CreateFontW(-17,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,0,0,CLEARTYPE_QUALITY,0,L"Segoe UI");
        titleFont=CreateFontW(-30,0,0,0,FW_SEMIBOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,0,0,CLEARTYPE_QUALITY,0,L"Segoe UI");
        HWND heading=control(L"STATIC",L"PhoneCam",0,24,18,400,42);SendMessageW(heading,WM_SETFONT,(WPARAM)titleFont,TRUE);
        control(L"STATIC",L"Start the camera on the phone · join the same Wi-Fi",0,24,64,590,26);
        control(L"STATIC",L"Phone address",0,24,104,390,22);
        addressEdit=control(L"EDIT",L"",ES_AUTOHSCROLL|WS_TABSTOP,24,130,420,30,10);
        findButton=control(L"BUTTON",L"Find phone",WS_TABSTOP,454,129,154,32,11);
        wchar_t saved[512]={};GetPrivateProfileStringW(L"PhoneCam",L"Address",L"",saved,512,configFile.c_str());SetWindowTextW(addressEdit,saved);SendMessageW(addressEdit,EM_SETCUEBANNER,TRUE,(LPARAM)L"For example: 192.168.1.42:8080");
        control(L"STATIC",L"Code from phone",0,24,176,200,22);
        codeEdit=control(L"EDIT",L"",ES_AUTOHSCROLL|ES_NUMBER|WS_TABSTOP,24,202,150,32,12);SendMessageW(codeEdit,EM_SETLIMITTEXT,6,0);
        connectButton=control(L"BUTTON",L"Connect",WS_TABSTOP|BS_DEFPUSHBUTTON,188,201,180,34,13);
        control(L"BUTTON",L"Test camera",WS_TABSTOP,380,201,130,34,14);
        control(L"BUTTON",L"To tray",WS_TABSTOP,520,201,88,34,15);
        statusLabel=control(L"STATIC",L"Ready. The PhoneCam camera is registered for your user.",0,24,250,584,44);
        preview=control(L"PhoneCamPreview",L"",0,24,304,584,328);
        control(L"STATIC",L"In your calling app, select “PhoneCam”. Closing this window stops streaming.",0,24,646,584,46);
        SetTimer(h,1,1000,NULL);SetTimer(h,2,100,NULL);return 0;
    }
    case WM_CTLCOLORSTATIC:SetBkMode((HDC)w,TRANSPARENT);return (LRESULT)GetStockObject(WHITE_BRUSH);
    case RestoreMessage:tray(false);ShowWindow(h,SW_RESTORE);SetForegroundWindow(h);return 0;
    case WM_COMMAND:switch(LOWORD(w)){
        case 11:discover();break;case 13:if(process)stop();else start(false);break;case 14:start(true);break;case 15:tray(true);break;
        case 20:tray(false);ShowWindow(h,SW_RESTORE);SetForegroundWindow(h);break;case 21:DestroyWindow(h);break;
    }return 0;
    case DiscoveryMessage:{auto result=(std::wstring*)l;EnableWindow(findButton,TRUE);if(!result->empty()){SetWindowTextW(addressEdit,result->c_str());SetWindowTextW(statusLabel,L"Phone found. Enter the code from its screen.");SetFocus(codeEdit);}else SetWindowTextW(statusLabel,L"Not found. Start streaming on the phone or enter the IP manually.");delete result;return 0;}
    case WM_TIMER:
        if(w==2){if(!inTray&&!IsIconic(h))refreshPreview();return 0;}
        if(process){
            std::ifstream file(statusFile,std::ios::binary);std::string contents((std::istreambuf_iterator<char>(file)),{});
            if(WaitForSingleObject(process,0)==WAIT_OBJECT_0){stop();if(!contents.empty())SetWindowTextW(statusLabel,wide(contents).c_str());}
            else if(!contents.empty())SetWindowTextW(statusLabel,wide(contents).c_str());
        }return 0;
    case TrayMessage:
        if(l==WM_LBUTTONUP||l==WM_LBUTTONDBLCLK){tray(false);ShowWindow(h,SW_RESTORE);SetForegroundWindow(h);}
        if(l==WM_RBUTTONUP){HMENU menu=CreatePopupMenu();AppendMenuW(menu,MF_STRING,20,L"Open PhoneCam");AppendMenuW(menu,MF_STRING,21,L"Stop and exit");POINT p;GetCursorPos(&p);SetForegroundWindow(h);TrackPopupMenu(menu,TPM_RIGHTBUTTON,p.x,p.y,0,h,NULL);DestroyMenu(menu);}return 0;
    case WM_DESTROY:tray(false);stop();if(mapped)UnmapViewOfFile(mapped);if(mapping)CloseHandle(mapping);DeleteObject(font);DeleteObject(titleFont);PostQuitMessage(0);return 0;
    }return DefWindowProcW(h,msg,w,l);
}
int WINAPI wWinMain(HINSTANCE h,HINSTANCE,LPWSTR command,int show) {
    instance=h;SetProcessDPIAware();CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);WSADATA data;WSAStartup(MAKEWORD(2,2),&data);
    if(!prepare()){if(wcscmp(command,L"--install")==0||wcscmp(command,L"--uninstall")==0)return 3;MessageBoxW(NULL,L"Could not unpack PhoneCam. Close apps that use the old camera version and try again.",L"PhoneCam",MB_ICONERROR);return 1;}
    if(wcscmp(command,L"--install")==0)return registration()?0:2;
    if(wcscmp(command,L"--uninstall")==0)return registration(true)?0:2;
    HANDLE single=CreateMutexW(NULL,FALSE,L"Local\\PhoneCam_Window_v1");
    if(GetLastError()==ERROR_ALREADY_EXISTS){HWND other=FindWindowW(L"PhoneCamWindow",NULL);if(other){PostMessageW(other,RestoreMessage,0,0);}return 0;}
    if(!registration()){MessageBoxW(NULL,L"Could not register the virtual camera.",L"PhoneCam",MB_ICONERROR);return 2;}
    WNDCLASSW wc={};wc.hInstance=h;wc.lpfnWndProc=previewProc;wc.lpszClassName=L"PhoneCamPreview";RegisterClassW(&wc);
    wc.lpfnWndProc=procedure;wc.lpszClassName=L"PhoneCamWindow";wc.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);wc.hCursor=LoadCursorW(NULL,IDC_ARROW);wc.hIcon=LoadIconW(instance,MAKEINTRESOURCEW(201));RegisterClassW(&wc);
    HWND main=CreateWindowW(wc.lpszClassName,L"PhoneCam 1.1",WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_MINIMIZEBOX,CW_USEDEFAULT,CW_USEDEFAULT,650,750,NULL,NULL,h,NULL);
    ShowWindow(main,show);MSG msg;while(GetMessageW(&msg,NULL,0,0)>0){if(!IsDialogMessageW(main,&msg)){TranslateMessage(&msg);DispatchMessageW(&msg);}}
    CloseHandle(single);WSACleanup();CoUninitialize();return 0;
}



