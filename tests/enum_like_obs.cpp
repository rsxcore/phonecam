// Enumerates video capture devices the way OBS's win-dshow plugin does
// (STA thread, pin found by category, formats read through IAMStreamConfig),
// to diagnose a device that other DirectShow apps see but OBS does not.
#include <windows.h>
#include <dshow.h>
#include <stdio.h>

int main() {
    CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    ICreateDevEnum* devEnum = NULL;
    CoCreateInstance(CLSID_SystemDeviceEnum, NULL, CLSCTX_INPROC_SERVER, IID_ICreateDevEnum, (void**)&devEnum);
    IEnumMoniker* monikers = NULL;
    if (devEnum->CreateClassEnumerator(CLSID_VideoInputDeviceCategory, &monikers, 0) != S_OK) return 1;
    IMoniker* m = NULL;
    while (monikers->Next(1, &m, NULL) == S_OK) {
        IPropertyBag* bag = NULL;
        m->BindToStorage(NULL, NULL, IID_IPropertyBag, (void**)&bag);
        VARIANT name; VariantInit(&name);
        bag->Read(L"FriendlyName", &name, NULL);
        VARIANT path; VariantInit(&path);
        HRESULT hrPath = bag->Read(L"DevicePath", &path, NULL);
        wprintf(L"\n%s  (DevicePath %s)\n", name.bstrVal, SUCCEEDED(hrPath) ? path.bstrVal : L"<none>");
        IBaseFilter* filter = NULL;
        HRESULT hr = m->BindToObject(NULL, NULL, IID_IBaseFilter, (void**)&filter);
        printf("  BindToObject: 0x%08lX\n", hr);
        if (FAILED(hr)) continue;
        IEnumPins* pins = NULL;
        filter->EnumPins(&pins);
        IPin* pin = NULL;
        while (pins->Next(1, &pin, NULL) == S_OK) {
            PIN_DIRECTION dir; pin->QueryDirection(&dir);
            IKsPropertySet* ks = NULL;
            GUID cat = GUID_NULL; DWORD got = 0;
            HRESULT hks = pin->QueryInterface(IID_IKsPropertySet, (void**)&ks);
            if (SUCCEEDED(hks)) { hks = ks->Get(AMPROPSETID_Pin, AMPROPERTY_PIN_CATEGORY, NULL, 0, &cat, sizeof(cat), &got); ks->Release(); }
            printf("  pin dir=%d category=%s (0x%08lX, %lu bytes)\n", dir, IsEqualGUID(cat, PIN_CATEGORY_CAPTURE) ? "CAPTURE" : "other", hks, got);
            IEnumMediaTypes* types = NULL;
            int video = 0;
            if (SUCCEEDED(pin->EnumMediaTypes(&types))) {
                AM_MEDIA_TYPE* mt = NULL;
                while (types->Next(1, &mt, NULL) == S_OK) { if (IsEqualGUID(mt->majortype, MEDIATYPE_Video)) video++; if (mt->pbFormat) CoTaskMemFree(mt->pbFormat); CoTaskMemFree(mt); }
                types->Release();
            }
            printf("  EnumMediaTypes: %d video types\n", video);
            IAMStreamConfig* config = NULL;
            if (SUCCEEDED(pin->QueryInterface(IID_IAMStreamConfig, (void**)&config))) {
                int count = 0, size = 0;
                config->GetNumberOfCapabilities(&count, &size);
                printf("  caps: %d (size %d)\n", count, size);
                for (int i = 0; i < count && i < 3; ++i) {
                    AM_MEDIA_TYPE* mt = NULL; BYTE scc[256];
                    HRESULT hc = config->GetStreamCaps(i, &mt, scc);
                    if (SUCCEEDED(hc) && mt) {
                        VIDEOINFOHEADER* v = (VIDEOINFOHEADER*)mt->pbFormat;
                        printf("    %ldx%ld sub=%.4s interval=%lld\n", v->bmiHeader.biWidth, v->bmiHeader.biHeight, (char*)&mt->subtype.Data1, v->AvgTimePerFrame);
                        CoTaskMemFree(mt->pbFormat); CoTaskMemFree(mt);
                    }
                }
                config->Release();
            }
            pin->Release();
        }
        pins->Release();
        filter->Release();
    }
    return 0;
}
