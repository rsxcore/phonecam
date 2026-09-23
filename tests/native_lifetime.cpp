#include "../vcam/PhoneCamFilter.cpp"
#include <assert.h>
#include <stdio.h>
int main() {
    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    assert(DllCanUnloadNow() == S_OK);
    for (int i=0;i<1000;++i) {
        IClassFactory* factory=NULL;
        assert(SUCCEEDED(DllGetClassObject(CLSID_PhoneCamFilter,IID_IClassFactory,(void**)&factory)));
        assert(DllCanUnloadNow() == S_FALSE);
        factory->LockServer(TRUE);factory->Release();assert(DllCanUnloadNow()==S_FALSE);
        assert(SUCCEEDED(DllGetClassObject(CLSID_PhoneCamFilter,IID_IClassFactory,(void**)&factory)));
        factory->LockServer(FALSE);
        IBaseFilter* filter=NULL;assert(SUCCEEDED(factory->CreateInstance(NULL,IID_IBaseFilter,(void**)&filter)));factory->Release();
        IPin* pin=NULL;assert(SUCCEEDED(filter->FindPin(L"Capture",&pin)));filter->Release();
        assert(DllCanUnloadNow()==S_FALSE);
        PIN_INFO info={};assert(SUCCEEDED(pin->QueryPinInfo(&info)));info.pFilter->Release();
        IAMStreamConfig* config=NULL;assert(SUCCEEDED(pin->QueryInterface(IID_IAMStreamConfig,(void**)&config)));
        AM_MEDIA_TYPE* media=(AM_MEDIA_TYPE*)0x1;VIDEO_STREAM_CONFIG_CAPS caps={};
        assert(SUCCEEDED(config->GetStreamCaps(0,&media,(BYTE*)&caps)));assert(media->pbFormat);assert(caps.MaxFrameInterval==caps.MinFrameInterval);
        auto video=(VIDEOINFOHEADER*)media->pbFormat;video->bmiHeader.biHeight=720;assert(SUCCEEDED(config->SetFormat(media)));
        video->bmiHeader.biWidth=2147483647;assert(FAILED(config->SetFormat(media)));
        video->bmiHeader.biWidth=1920;video->bmiHeader.biHeight=-1080;assert(FAILED(config->SetFormat(media))); // NV12 must be top-down
        video->bmiHeader.biHeight=1081;assert(FAILED(config->SetFormat(media))); // odd sizes break chroma
        FreeMediaTypeContents(media);CoTaskMemFree(media);config->Release();
        IEnumMediaTypes* types=NULL;assert(SUCCEEDED(pin->EnumMediaTypes(&types)));pin->Release();assert(DllCanUnloadNow()==S_FALSE);
        types->Release();assert(DllCanUnloadNow()==S_OK);
    }
    // Pixels: a 4x2 white NV12 frame fitted into 8x2 lands centred between black bars.
    BYTE src[12];memset(src,235,8);memset(src+8,128,4);
    BYTE dst[24];ScaleNv12(src,4,2,dst,8,2);
    assert(dst[0]==16&&dst[1]==16&&dst[2]==235&&dst[5]==235&&dst[6]==16);
    BYTE rgb[64];Nv12ToRgb32(src,4,2,rgb,8,2,false);
    assert(((UINT32*)rgb)[0]==0&&(((UINT32*)rgb)[3]&0xFFFFFF)==0xFFFFFF);
    ScaleNv12(src,4,2,dst,4,2);assert(memcmp(src,dst,12)==0);
    CoUninitialize();puts("PASS: 1000 COM lifetime, format allocation, validation and DLL unload cycles");
}
