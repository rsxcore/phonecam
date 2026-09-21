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
        FreeMediaTypeContents(media);CoTaskMemFree(media);config->Release();
        IEnumMediaTypes* types=NULL;assert(SUCCEEDED(pin->EnumMediaTypes(&types)));pin->Release();assert(DllCanUnloadNow()==S_FALSE);
        types->Release();assert(DllCanUnloadNow()==S_OK);
    }
    CoUninitialize();puts("PASS: 1000 COM lifetime, format allocation, validation and DLL unload cycles");
}
