/*
 * PhoneCamFilter.cpp
 *
 * "PhoneCam" — a DirectShow video capture source that presents frames from a
 * shared memory buffer as an ordinary webcam. Zoom, Discord, Teams, Chrome and
 * anything else that enumerates video input devices will list it.
 *
 * Why DirectShow and not the Windows 11 Media Foundation virtual camera API:
 * MFCreateVirtualCamera requires a hand-written IMFMediaSource (far more COM
 * surface) and an app to re-create the camera after every boot. A DirectShow
 * filter registers once with regsvr32 and just stays there, and Media
 * Foundation still bridges to DirectShow capture devices, so modern apps see
 * it either way.
 *
 * Self-contained on purpose. The DirectShow base classes no longer ship with
 * the Windows SDK, and pulling them from a mirror would mean vendoring a few
 * thousand lines of someone else's code into a project meant to be ours alone.
 * Everything below is written against dshow.h only.
 */

#define WIN32_LEAN_AND_MEAN
#define _CRT_SECURE_NO_WARNINGS

#include <windows.h>
#include <initguid.h> /* must precede dshow.h: instantiates the DShow GUIDs here */
#include <dshow.h>
#include <olectl.h>

#include "PhoneCamProtocol.h"

/* {32A4046A-5361-491F-AD92-F30755E23446} */
static const GUID CLSID_PhoneCamFilter = {
    0x32a4046a, 0x5361, 0x491f, {0xad, 0x92, 0xf3, 0x07, 0x55, 0xe2, 0x34, 0x46}};

static const wchar_t kFilterName[] = L"PhoneCam";
static const wchar_t kPinName[] = L"Capture";

/* Formats offered to the graph. Apps overwhelmingly default to the first
 * entry, so 1080p60 NV12 leads: it is what OBS handles natively, with no
 * colour conversion anywhere between the phone's encoder and OBS. RGB32 stays
 * for older apps that only speak RGB. Portrait sizes exist for vertical video. */
struct Cap {
    UINT w, h, fps;
    bool nv12;
};
static const Cap kCaps[] = {
    {1920, 1080, 60, true}, {1920, 1080, 30, true}, {1280, 720, 60, true},  {1280, 720, 30, true},
    {3840, 2160, 30, true}, {1080, 1920, 60, true}, {1080, 1920, 30, true}, {1920, 1080, 30, false},
    {1280, 720, 30, false},
};
static const int kCapCount = (int)(sizeof(kCaps) / sizeof(kCaps[0]));

static const DWORD FOURCC_NV12 = MAKEFOURCC('N', 'V', '1', '2');

static HINSTANCE g_hInst = NULL;
static LONG g_cLockedObjects = 0;
static LONG g_cServerLocks = 0;

/* ------------------------------------------------------------------ */
/* Small COM helpers                                                   */
/* ------------------------------------------------------------------ */

/* Media type blocks are handed to the graph, which frees them with
 * CoTaskMemFree. We allocate them, so we also need to be able to clean up
 * partially built ones on the error paths. */
static void FreeMediaTypeContents(AM_MEDIA_TYPE* pmt) {
    if (!pmt) return;
    if (pmt->cbFormat != 0 && pmt->pbFormat) {
        CoTaskMemFree(pmt->pbFormat);
        pmt->pbFormat = NULL;
        pmt->cbFormat = 0;
    }
    if (pmt->pUnk) {
        pmt->pUnk->Release();
        pmt->pUnk = NULL;
    }
}

static UINT FrameBytes(UINT w, UINT h, bool nv12) { return nv12 ? w * h * 3 / 2 : w * h * 4; }

static bool SizeOk(UINT w, UINT h) {
    return w >= 2 && h >= 2 && w % 2 == 0 && h % 2 == 0 && w <= PHONECAM_MAX_WIDTH &&
           h <= PHONECAM_MAX_HEIGHT && (UINT64)w * h <= PHONECAM_MAX_PIXELS;
}

/* Parses a media type we can produce. Out-params are optional. */
static bool ParseFormat(const AM_MEDIA_TYPE* pmt, UINT* w, UINT* h, UINT* fps, bool* nv12) {
    if (!pmt) return false;
    if (pmt->majortype != MEDIATYPE_Video) return false;
    if (pmt->formattype != FORMAT_VideoInfo) return false;
    if (pmt->cbFormat < sizeof(VIDEOINFOHEADER) || !pmt->pbFormat) return false;
    const VIDEOINFOHEADER* vih = (const VIDEOINFOHEADER*)pmt->pbFormat;
    const BITMAPINFOHEADER& b = vih->bmiHeader;
    bool isNv12;
    if (pmt->subtype == MEDIASUBTYPE_NV12 && b.biCompression == FOURCC_NV12 && b.biBitCount == 12) isNv12 = true;
    else if (pmt->subtype == MEDIASUBTYPE_RGB32 && b.biCompression == BI_RGB && b.biBitCount == 32) isNv12 = false;
    else return false;
    if (b.biWidth <= 0 || b.biHeight == 0 || b.biPlanes != 1) return false;
    if (isNv12 && b.biHeight < 0) return false; /* YUV is always top-down */
    const UINT ww = (UINT)b.biWidth, hh = (UINT)abs(b.biHeight);
    if (!SizeOk(ww, hh)) return false;
    UINT f = vih->AvgTimePerFrame > 0 ? (UINT)((10000000LL + vih->AvgTimePerFrame / 2) / vih->AvgTimePerFrame) : 30;
    if (f < 1) f = 1;
    if (f > 60) f = 60;
    if (w) *w = ww;
    if (h) *h = hh;
    if (fps) *fps = f;
    if (nv12) *nv12 = isNv12;
    return true;
}

static bool IsAcceptableFormat(const AM_MEDIA_TYPE* pmt) { return ParseFormat(pmt, NULL, NULL, NULL, NULL); }

static void FillVideoInfoHeader(AM_MEDIA_TYPE* pmt, UINT w, UINT h, UINT fps, bool nv12) {
    pmt->majortype = MEDIATYPE_Video;
    pmt->subtype = nv12 ? MEDIASUBTYPE_NV12 : MEDIASUBTYPE_RGB32;
    pmt->formattype = FORMAT_VideoInfo;
    pmt->bFixedSizeSamples = TRUE;
    pmt->bTemporalCompression = FALSE;
    pmt->lSampleSize = (ULONG)FrameBytes(w, h, nv12);
    pmt->pUnk = NULL;
    pmt->cbFormat = sizeof(VIDEOINFOHEADER);
    pmt->pbFormat = (BYTE*)CoTaskMemAlloc(sizeof(VIDEOINFOHEADER));
    if (!pmt->pbFormat) return;

    VIDEOINFOHEADER* vih = (VIDEOINFOHEADER*)pmt->pbFormat;
    ZeroMemory(vih, sizeof(VIDEOINFOHEADER));
    vih->AvgTimePerFrame = 10000000LL / fps;
    vih->dwBitRate = (DWORD)((UINT64)FrameBytes(w, h, nv12) * 8 * fps);
    vih->rcSource.right = w;
    vih->rcSource.bottom = h;
    vih->rcTarget = vih->rcSource;

    BITMAPINFOHEADER* bih = &vih->bmiHeader;
    bih->biSize = sizeof(BITMAPINFOHEADER);
    bih->biWidth = (LONG)w;
    /* RGB: negative height = top-down rows, like the shared memory. YUV
     * formats are top-down by definition and must use a positive height. */
    bih->biHeight = nv12 ? (LONG)h : -(LONG)h;
    bih->biPlanes = 1;
    bih->biBitCount = nv12 ? 12 : 32;
    bih->biCompression = nv12 ? FOURCC_NV12 : BI_RGB;
    bih->biSizeImage = FrameBytes(w, h, nv12);
}

/* ------------------------------------------------------------------ */
/* Pixel plumbing                                                      */
/* ------------------------------------------------------------------ */

/* Deliberately not black: a dark blue-grey tells you at a glance that the
 * filter is loaded and running, as opposed to the app having failed to open
 * the camera at all. */
static void FillNoSignal(BYTE* dst, UINT w, UINT h, bool nv12) {
    if (nv12) {
        memset(dst, 30, (size_t)w * h);
        BYTE* uv = dst + (size_t)w * h;
        for (size_t i = 0; i < (size_t)w * h / 2; i += 2) {
            uv[i] = 136;
            uv[i + 1] = 124;
        }
        return;
    }
    const UINT32 px = 0x001E1418u; /* BGRA */
    UINT32* p = (UINT32*)dst;
    for (size_t i = 0; i < (size_t)w * h; ++i) p[i] = px;
}

/* Where the source lands inside the destination once scaled to fit with the
 * aspect ratio preserved. Offsets and sizes are even so NV12 chroma lines up. */
struct Fit {
    UINT ox, oy, ow, oh;
};

static Fit FitInside(UINT sw, UINT sh, UINT dw, UINT dh) {
    Fit f;
    if ((UINT64)sw * dh > (UINT64)sh * dw) { /* wider than the target: bars top and bottom */
        f.ow = dw;
        f.oh = (UINT)((UINT64)sh * dw / sw) & ~1u;
    } else {
        f.oh = dh;
        f.ow = (UINT)((UINT64)sw * dh / sh) & ~1u;
    }
    if (f.ow < 2) f.ow = 2;
    if (f.oh < 2) f.oh = 2;
    f.ox = ((dw - f.ow) / 2) & ~1u;
    f.oy = ((dh - f.oh) / 2) & ~1u;
    return f;
}

/* NV12 → NV12 with aspect-preserving scaling. Same size is a plain copy, the
 * common case: phone 1080p into a 1080p camera. Scaling uses bilinear luma
 * and nearest chroma, which is plenty for a picture that is being letterboxed
 * or downscaled anyway. */
static void ScaleNv12(const BYTE* src, UINT sw, UINT sh, BYTE* dst, UINT dw, UINT dh) {
    if (sw == dw && sh == dh) {
        memcpy(dst, src, (size_t)dw * dh * 3 / 2);
        return;
    }
    const Fit f = FitInside(sw, sh, dw, dh);
    memset(dst, 16, (size_t)dw * dh);
    memset(dst + (size_t)dw * dh, 128, (size_t)dw * dh / 2);

    const UINT64 stepX = ((UINT64)sw << 16) / f.ow, stepY = ((UINT64)sh << 16) / f.oh;
    for (UINT y = 0; y < f.oh; ++y) {
        UINT64 fy = y * stepY + stepY / 2;
        fy = fy > 32768 ? fy - 32768 : 0;
        UINT y0 = (UINT)(fy >> 16);
        if (y0 >= sh - 1) y0 = sh - 2;
        const UINT wy = (UINT)(fy & 0xFFFF) >> 8;
        const BYTE* r0 = src + (size_t)y0 * sw;
        const BYTE* r1 = r0 + sw;
        BYTE* out = dst + (size_t)(y + f.oy) * dw + f.ox;
        for (UINT x = 0; x < f.ow; ++x) {
            UINT64 fx = x * stepX + stepX / 2;
            fx = fx > 32768 ? fx - 32768 : 0;
            UINT x0 = (UINT)(fx >> 16);
            if (x0 >= sw - 1) x0 = sw - 2;
            const UINT wx = (UINT)(fx & 0xFFFF) >> 8;
            const UINT top = r0[x0] * (256 - wx) + r0[x0 + 1] * wx;
            const UINT bot = r1[x0] * (256 - wx) + r1[x0 + 1] * wx;
            out[x] = (BYTE)((top * (256 - wy) + bot * wy + 32768) >> 16);
        }
    }
    const BYTE* suv = src + (size_t)sw * sh;
    BYTE* duv = dst + (size_t)dw * dh;
    for (UINT y = 0; y < f.oh / 2; ++y) {
        const UINT sy = (UINT)((UINT64)y * sh / f.oh);
        const UINT16* srow = (const UINT16*)(suv + (size_t)sy * sw);
        UINT16* drow = (UINT16*)(duv + (size_t)(y + f.oy / 2) * dw + f.ox);
        for (UINT x = 0; x < f.ow / 2; ++x) {
            drow[x] = srow[(UINT)((UINT64)x * sw / f.ow)];
        }
    }
}

static inline BYTE Clamp255(int v) { return (BYTE)(v < 0 ? 0 : v > 255 ? 255 : v); }

/* NV12 (BT.709, limited range, as phone encoders produce) → BGRA with
 * aspect-preserving nearest-neighbour scaling, for apps that insist on RGB. */
static void Nv12ToRgb32(const BYTE* src, UINT sw, UINT sh, BYTE* dst, UINT dw, UINT dh, bool bottomUp) {
    const Fit f = FitInside(sw, sh, dw, dh);
    memset(dst, 0, (size_t)dw * dh * 4);
    const BYTE* suv = src + (size_t)sw * sh;
    for (UINT y = 0; y < f.oh; ++y) {
        const UINT sy = (UINT)((UINT64)y * sh / f.oh);
        const BYTE* yrow = src + (size_t)sy * sw;
        const BYTE* uvrow = suv + (size_t)(sy / 2) * sw;
        const UINT dy = bottomUp ? dh - 1 - (y + f.oy) : y + f.oy;
        UINT32* out = (UINT32*)(dst + (size_t)dy * dw * 4) + f.ox;
        for (UINT x = 0; x < f.ow; ++x) {
            const UINT sx = (UINT)((UINT64)x * sw / f.ow);
            const int c = (yrow[sx] - 16) * 298;
            const int d = uvrow[sx & ~1u] - 128;
            const int e = uvrow[(sx & ~1u) + 1] - 128;
            const BYTE r = Clamp255((c + 459 * e + 128) >> 8);
            const BYTE g = Clamp255((c - 55 * d - 136 * e + 128) >> 8);
            const BYTE b = Clamp255((c + 541 * d + 128) >> 8);
            out[x] = 0xFF000000u | ((UINT32)r << 16) | ((UINT32)g << 8) | b;
        }
    }
}

/* ------------------------------------------------------------------ */
/* EnumPins                                                            */
/* ------------------------------------------------------------------ */

class PhoneCamPin;
class PhoneCamFilter;

class EnumPins : public IEnumPins {
  public:
    explicit EnumPins(PhoneCamPin* pPin);
    ~EnumPins();

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv);
    STDMETHODIMP_(ULONG) AddRef();
    STDMETHODIMP_(ULONG) Release();

    STDMETHODIMP Next(ULONG cPins, IPin** ppPins, ULONG* pcFetched);
    STDMETHODIMP Skip(ULONG cPins);
    STDMETHODIMP Reset();
    STDMETHODIMP Clone(IEnumPins** ppEnum);

  private:
    LONG m_ref;
    PhoneCamPin* m_pPin; /* holds a reference, so the pin cannot die mid-walk */
    ULONG m_pos;
};

/* ------------------------------------------------------------------ */
/* EnumMediaTypes                                                      */
/* ------------------------------------------------------------------ */

class EnumMediaTypes : public IEnumMediaTypes {
  public:
    EnumMediaTypes();
    ~EnumMediaTypes();

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv);
    STDMETHODIMP_(ULONG) AddRef();
    STDMETHODIMP_(ULONG) Release();

    STDMETHODIMP Next(ULONG cTypes, AM_MEDIA_TYPE** ppTypes, ULONG* pcFetched);
    STDMETHODIMP Skip(ULONG cTypes);
    STDMETHODIMP Reset();
    STDMETHODIMP Clone(IEnumMediaTypes** ppEnum);

  private:
    LONG m_ref;
    ULONG m_pos;
};

/* ------------------------------------------------------------------ */
/* The output pin                                                      */
/* ------------------------------------------------------------------ */

class PhoneCamPin : public IPin, public IAMStreamConfig, public IKsPropertySet {
  public:
    PhoneCamPin(PhoneCamFilter* pFilter, HRESULT* phr);
    ~PhoneCamPin();

    /* IUnknown */
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv);
    STDMETHODIMP_(ULONG) AddRef();
    STDMETHODIMP_(ULONG) Release();

    /* IPin */
    STDMETHODIMP Connect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt);
    STDMETHODIMP ReceiveConnection(IPin* pConnector, const AM_MEDIA_TYPE* pmt);
    STDMETHODIMP Disconnect();
    STDMETHODIMP ConnectedTo(IPin** ppPin);
    STDMETHODIMP ConnectionMediaType(AM_MEDIA_TYPE* pmt);
    STDMETHODIMP QueryPinInfo(PIN_INFO* pInfo);
    STDMETHODIMP QueryDirection(PIN_DIRECTION* pPinDir);
    STDMETHODIMP QueryId(LPWSTR* Id);
    STDMETHODIMP QueryAccept(const AM_MEDIA_TYPE* pmt);
    STDMETHODIMP EnumMediaTypes(IEnumMediaTypes** ppEnum);
    STDMETHODIMP QueryInternalConnections(IPin** apPin, ULONG* nPin);
    STDMETHODIMP EndOfStream();
    STDMETHODIMP BeginFlush();
    STDMETHODIMP EndFlush();
    STDMETHODIMP NewSegment(REFERENCE_TIME tStart, REFERENCE_TIME tStop,
                            double dRate);

    /* IAMStreamConfig. The SDK's SetFormat takes a non-const pointer, and a
     * const one here would be a different function that never overrides it. */
    STDMETHODIMP SetFormat(AM_MEDIA_TYPE* pmt);
    STDMETHODIMP GetFormat(AM_MEDIA_TYPE** ppmt);
    STDMETHODIMP GetNumberOfCapabilities(int* piCount, int* piSize);
    STDMETHODIMP GetStreamCaps(int iIndex, AM_MEDIA_TYPE** ppmt, BYTE* pSCC);

    /* IKsPropertySet */
    STDMETHODIMP Set(REFGUID guidPropSet, DWORD dwPropID, LPVOID pInstanceData,
                     DWORD cbInstanceData, LPVOID pPropData, DWORD cbPropData);
    STDMETHODIMP Get(REFGUID guidPropSet, DWORD dwPropID, LPVOID pInstanceData,
                     DWORD cbInstanceData, LPVOID pPropData, DWORD cbPropData,
                     DWORD* pcbReturned);
    STDMETHODIMP QuerySupported(REFGUID guidPropSet, DWORD dwPropID,
                                DWORD* pTypeSupport);

    /* Called by the filter. */
    void StartStreaming();
    void StopStreaming();
    bool IsStreaming() const { return m_hThread != NULL; }

  private:
    static DWORD WINAPI ThreadEntry(LPVOID param);
    void StreamLoop();
    void DeliverOneFrame();

    bool OpenSharedMemory();
    void CloseSharedMemory();
    /* Copies the newest frame into dst, fitted to the negotiated format. False
     * if there is nothing to show. */
    bool CopyLatestFrame(BYTE* dst, UINT32* index, INT64* ptsUs, UINT* fps);
    REFERENCE_TIME StreamTime();

    LONG m_ref;
    PhoneCamFilter* m_pFilter;
    IPin* m_pDownstream;
    /* NotifyAllocator and Receive live on IMemInputPin, not on IPin, so the
     * connection has to be cast once and kept. */
    IMemInputPin* m_pDownstreamInput;
    IMemAllocator* m_pAllocator;
    AM_MEDIA_TYPE m_mt; /* connection type; only valid while connected */

    UINT m_width, m_height, m_fps;
    bool m_nv12;
    bool m_bottomUp;

    HANDLE m_hMap;
    BYTE* m_pView;
    /* Tick of the last failed attempt to open the mapping. The server can start
     * after us, so we keep retrying — but not 30 times a second. */
    DWORD m_lastOpenTry;

    HANDLE m_hThread;
    HANDLE m_hStop;

    /* Timestamps: phone capture times mapped onto stream time, so frames that
     * arrive unevenly over Wi-Fi still carry evenly spaced timestamps. */
    UINT32 m_lastIndex;
    bool m_haveBase;
    REFERENCE_TIME m_rtBase;
    INT64 m_ptsBase;
    REFERENCE_TIME m_rtLast;
    LARGE_INTEGER m_qpcStart;

  public:
    REFERENCE_TIME m_tStart; /* from IMediaFilter::Run */
};

/* ------------------------------------------------------------------ */
/* The filter                                                          */
/* ------------------------------------------------------------------ */

class PhoneCamFilter : public IBaseFilter {
  public:
    PhoneCamFilter(HRESULT* phr);
    ~PhoneCamFilter();

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv);
    STDMETHODIMP_(ULONG) AddRef();
    STDMETHODIMP_(ULONG) Release();

    /* IPersist */
    STDMETHODIMP GetClassID(CLSID* pClsID);

    /* IMediaFilter */
    STDMETHODIMP Stop();
    STDMETHODIMP Pause();
    STDMETHODIMP Run(REFERENCE_TIME tStart);
    STDMETHODIMP GetState(DWORD dwMilliSecsTimeout, FILTER_STATE* pState);
    STDMETHODIMP SetSyncSource(IReferenceClock* pClock);
    STDMETHODIMP GetSyncSource(IReferenceClock** ppClock);

    /* IBaseFilter */
    STDMETHODIMP EnumPins(IEnumPins** ppEnum);
    STDMETHODIMP FindPin(LPCWSTR Id, IPin** ppPin);
    STDMETHODIMP QueryFilterInfo(FILTER_INFO* pInfo);
    STDMETHODIMP JoinFilterGraph(IFilterGraph* pGraph, LPCWSTR pName);
    STDMETHODIMP QueryVendorInfo(LPWSTR* pVendorInfo);

    PhoneCamPin* Pin() const { return m_pPin; }
    IReferenceClock* Clock() const { return m_pClock; }
    FILTER_STATE State() const { return m_state; }

  private:
    LONG m_ref;
    PhoneCamPin* m_pPin;
    IFilterGraph* m_pGraph;
    IReferenceClock* m_pClock;
    FILTER_STATE m_state;
};

/* ------------------------------------------------------------------ */
/* EnumPins implementation                                             */
/* ------------------------------------------------------------------ */

EnumPins::EnumPins(PhoneCamPin* pPin) : m_ref(1), m_pPin(pPin), m_pos(0) {
    m_pPin->AddRef();
}

EnumPins::~EnumPins() { m_pPin->Release(); }

STDMETHODIMP EnumPins::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IEnumPins) {
        *ppv = (IEnumPins*)this;
        AddRef();
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) EnumPins::AddRef() {
    return (ULONG)InterlockedIncrement(&m_ref);
}

STDMETHODIMP_(ULONG) EnumPins::Release() {
    const LONG r = InterlockedDecrement(&m_ref);
    if (r == 0) delete this;
    return (ULONG)r;
}

STDMETHODIMP EnumPins::Next(ULONG cPins, IPin** ppPins, ULONG* pcFetched) {
    if (!ppPins) return E_POINTER;
    if (pcFetched) *pcFetched = 0;
    else if (cPins > 1) return E_INVALIDARG;

    ULONG fetched = 0;
    while (fetched < cPins && m_pos < 1) {
        ppPins[fetched] = m_pPin;
        m_pPin->AddRef();
        ++fetched;
        ++m_pos;
    }
    if (pcFetched) *pcFetched = fetched;
    return fetched == cPins ? S_OK : S_FALSE;
}

STDMETHODIMP EnumPins::Skip(ULONG cPins) {
    m_pos += cPins;
    return m_pos <= 1 ? S_OK : S_FALSE;
}

STDMETHODIMP EnumPins::Reset() {
    m_pos = 0;
    return S_OK;
}

STDMETHODIMP EnumPins::Clone(IEnumPins** ppEnum) {
    if (!ppEnum) return E_POINTER;
    EnumPins* e = new EnumPins(m_pPin);
    if (!e) return E_OUTOFMEMORY;
    e->m_pos = m_pos;
    *ppEnum = e;
    return S_OK;
}

/* ------------------------------------------------------------------ */
/* EnumMediaTypes implementation                                       */
/* ------------------------------------------------------------------ */

EnumMediaTypes::EnumMediaTypes() : m_ref(1), m_pos(0) { InterlockedIncrement(&g_cLockedObjects); }

EnumMediaTypes::~EnumMediaTypes() { InterlockedDecrement(&g_cLockedObjects); }

STDMETHODIMP EnumMediaTypes::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IEnumMediaTypes) {
        *ppv = (IEnumMediaTypes*)this;
        AddRef();
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) EnumMediaTypes::AddRef() {
    return (ULONG)InterlockedIncrement(&m_ref);
}

STDMETHODIMP_(ULONG) EnumMediaTypes::Release() {
    const LONG r = InterlockedDecrement(&m_ref);
    if (r == 0) delete this;
    return (ULONG)r;
}

STDMETHODIMP EnumMediaTypes::Next(ULONG cTypes, AM_MEDIA_TYPE** ppTypes,
                                  ULONG* pcFetched) {
    if (!ppTypes) return E_POINTER;
    if (pcFetched) *pcFetched = 0;
    else if (cTypes > 1) return E_INVALIDARG;

    ULONG fetched = 0;
    while (fetched < cTypes && m_pos < (ULONG)kCapCount) {
        AM_MEDIA_TYPE* pmt = (AM_MEDIA_TYPE*)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
        if (!pmt) break;
        ZeroMemory(pmt, sizeof(AM_MEDIA_TYPE));
        FillVideoInfoHeader(pmt, kCaps[m_pos].w, kCaps[m_pos].h, kCaps[m_pos].fps, kCaps[m_pos].nv12);
        if (!pmt->pbFormat) {
            CoTaskMemFree(pmt);
            break;
        }
        ppTypes[fetched] = pmt;
        ++fetched;
        ++m_pos;
    }
    if (pcFetched) *pcFetched = fetched;
    return fetched == cTypes ? S_OK : S_FALSE;
}

STDMETHODIMP EnumMediaTypes::Skip(ULONG cTypes) {
    m_pos += cTypes;
    return m_pos <= (ULONG)kCapCount ? S_OK : S_FALSE;
}

STDMETHODIMP EnumMediaTypes::Reset() {
    m_pos = 0;
    return S_OK;
}

STDMETHODIMP EnumMediaTypes::Clone(IEnumMediaTypes** ppEnum) {
    if (!ppEnum) return E_POINTER;
    EnumMediaTypes* e = new EnumMediaTypes();
    if (!e) return E_OUTOFMEMORY;
    e->m_pos = m_pos;
    *ppEnum = e;
    return S_OK;
}

/* ------------------------------------------------------------------ */
/* PhoneCamPin implementation                                          */
/* ------------------------------------------------------------------ */

PhoneCamPin::PhoneCamPin(PhoneCamFilter* pFilter, HRESULT* phr)
    : m_ref(1),
      m_pFilter(pFilter),
      m_pDownstream(NULL),
      m_pDownstreamInput(NULL),
      m_pAllocator(NULL),
      m_width(kCaps[0].w),
      m_height(kCaps[0].h),
      m_fps(kCaps[0].fps),
      m_nv12(kCaps[0].nv12),
      m_bottomUp(false),
      m_hMap(NULL),
      m_pView(NULL),
      m_lastOpenTry(0),
      m_hThread(NULL),
      m_hStop(NULL),
      m_lastIndex(0),
      m_haveBase(false),
      m_rtBase(0),
      m_ptsBase(0),
      m_rtLast(0),
      m_tStart(0) {
    m_qpcStart.QuadPart = 0;
    ZeroMemory(&m_mt, sizeof(m_mt));
    m_hStop = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!m_hStop && phr) *phr = E_FAIL;
}

PhoneCamPin::~PhoneCamPin() {
    StopStreaming();
    Disconnect();
    CloseSharedMemory();
    if (m_hStop) CloseHandle(m_hStop);
    FreeMediaTypeContents(&m_mt);
}

STDMETHODIMP PhoneCamPin::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IPin) {
        *ppv = (IPin*)this;
    } else if (riid == IID_IAMStreamConfig) {
        *ppv = (IAMStreamConfig*)this;
    } else if (riid == IID_IKsPropertySet) {
        *ppv = (IKsPropertySet*)this;
    } else {
        *ppv = NULL;
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

STDMETHODIMP_(ULONG) PhoneCamPin::AddRef() {
    return m_pFilter->AddRef();
}

STDMETHODIMP_(ULONG) PhoneCamPin::Release() {
    return m_pFilter->Release();
}

STDMETHODIMP PhoneCamPin::Connect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt) {
    if (!pReceivePin) return E_POINTER;
    if (m_pDownstream) return VFW_E_ALREADY_CONNECTED;

    /* The graph usually passes NULL and expects us to offer something, so fall
     * back to the negotiated size rather than refusing. */
    AM_MEDIA_TYPE chosen;
    ZeroMemory(&chosen, sizeof(chosen));
    if (pmt) {
        if (!IsAcceptableFormat(pmt)) return VFW_E_TYPE_NOT_ACCEPTED;
        chosen = *pmt;
        chosen.pbFormat = (BYTE*)CoTaskMemAlloc(pmt->cbFormat);
        if (!chosen.pbFormat) return E_OUTOFMEMORY;
        memcpy(chosen.pbFormat, pmt->pbFormat, pmt->cbFormat);
        if (chosen.pUnk) chosen.pUnk->AddRef();
    } else {
        FillVideoInfoHeader(&chosen, m_width, m_height, m_fps, m_nv12);
        if (!chosen.pbFormat) return E_OUTOFMEMORY;
    }

    VIDEOINFOHEADER* vih = (VIDEOINFOHEADER*)chosen.pbFormat;
    UINT w = 0, h = 0, fps = 30;
    bool nv12 = true;
    if (!ParseFormat(&chosen, &w, &h, &fps, &nv12)) {
        FreeMediaTypeContents(&chosen);
        return VFW_E_TYPE_NOT_ACCEPTED;
    }

    HRESULT hr = CoCreateInstance(CLSID_MemoryAllocator, NULL, CLSCTX_INPROC_SERVER,
                                  IID_IMemAllocator, (void**)&m_pAllocator);
    if (FAILED(hr)) {
        FreeMediaTypeContents(&chosen);
        return hr;
    }

    ALLOCATOR_PROPERTIES props, actual;
    props.cBuffers = 3;
    props.cbBuffer = (long)FrameBytes(w, h, nv12);
    props.cbAlign = 1;
    props.cbPrefix = 0;
    hr = m_pAllocator->SetProperties(&props, &actual);
    if (FAILED(hr)) {
        m_pAllocator->Release();
        m_pAllocator = NULL;
        FreeMediaTypeContents(&chosen);
        return hr;
    }

    hr = pReceivePin->ReceiveConnection(this, &chosen);
    if (FAILED(hr)) {
        m_pAllocator->Release();
        m_pAllocator = NULL;
        FreeMediaTypeContents(&chosen);
        return hr;
    }

    hr = pReceivePin->QueryInterface(IID_IMemInputPin, (void**)&m_pDownstreamInput);
    if (FAILED(hr)) {
        pReceivePin->Disconnect();
        m_pAllocator->Release();
        m_pAllocator = NULL;
        FreeMediaTypeContents(&chosen);
        return hr;
    }

    hr = m_pAllocator->Commit();
    if (FAILED(hr)) {
        pReceivePin->Disconnect();
        m_pDownstreamInput->Release();
        m_pDownstreamInput = NULL;
        m_pAllocator->Release();
        m_pAllocator = NULL;
        FreeMediaTypeContents(&chosen);
        return hr;
    }

    /* We own the allocator: the downstream filter must not write into our
     * buffers, it only reads them. */
    m_pDownstreamInput->NotifyAllocator(m_pAllocator, TRUE);

    FreeMediaTypeContents(&m_mt);
    m_mt = chosen;
    m_bottomUp = !nv12 && vih->bmiHeader.biHeight > 0;

    m_width = w;
    m_height = h;
    m_fps = fps;
    m_nv12 = nv12;
    m_pDownstream = pReceivePin;
    m_pDownstream->AddRef();
    return S_OK;
}

STDMETHODIMP PhoneCamPin::ReceiveConnection(IPin* pConnector,
                                            const AM_MEDIA_TYPE* pmt) {
    /* We are a source. Downstream pins do not connect to us. */
    (void)pConnector;
    (void)pmt;
    return E_UNEXPECTED;
}

STDMETHODIMP PhoneCamPin::Disconnect() {
    StopStreaming();
    if (m_pAllocator) {
        m_pAllocator->Decommit();
        m_pAllocator->Release();
        m_pAllocator = NULL;
    }
    if (m_pDownstreamInput) {
        m_pDownstreamInput->Release();
        m_pDownstreamInput = NULL;
    }
    if (m_pDownstream) {
        m_pDownstream->Release();
        m_pDownstream = NULL;
    }
    FreeMediaTypeContents(&m_mt);
    ZeroMemory(&m_mt, sizeof(m_mt));
    return S_OK;
}

STDMETHODIMP PhoneCamPin::ConnectedTo(IPin** ppPin) {
    if (!ppPin) return E_POINTER;
    *ppPin = m_pDownstream;
    if (m_pDownstream) {
        m_pDownstream->AddRef();
        return S_OK;
    }
    return VFW_E_NOT_CONNECTED;
}

STDMETHODIMP PhoneCamPin::ConnectionMediaType(AM_MEDIA_TYPE* pmt) {
    if (!pmt) return E_POINTER;
    if (!m_pDownstream) return VFW_E_NOT_CONNECTED;
    *pmt = m_mt;
    if (m_mt.cbFormat) {
        pmt->pbFormat = (BYTE*)CoTaskMemAlloc(m_mt.cbFormat);
        if (!pmt->pbFormat) return E_OUTOFMEMORY;
        memcpy(pmt->pbFormat, m_mt.pbFormat, m_mt.cbFormat);
    }
    if (m_mt.pUnk) pmt->pUnk->AddRef();
    return S_OK;
}

STDMETHODIMP PhoneCamPin::QueryPinInfo(PIN_INFO* pInfo) {
    if (!pInfo) return E_POINTER;
    pInfo->pFilter = m_pFilter;
    if (m_pFilter) m_pFilter->AddRef();
    lstrcpynW(pInfo->achName, kPinName, sizeof(pInfo->achName) / sizeof(WCHAR));
    pInfo->dir = PINDIR_OUTPUT;
    return S_OK;
}

STDMETHODIMP PhoneCamPin::QueryDirection(PIN_DIRECTION* pPinDir) {
    if (!pPinDir) return E_POINTER;
    *pPinDir = PINDIR_OUTPUT;
    return S_OK;
}

STDMETHODIMP PhoneCamPin::QueryId(LPWSTR* Id) {
    if (!Id) return E_POINTER;
    const size_t bytes = (lstrlenW(kPinName) + 1) * sizeof(WCHAR);
    *Id = (LPWSTR)CoTaskMemAlloc(bytes);
    if (!*Id) return E_OUTOFMEMORY;
    memcpy(*Id, kPinName, bytes);
    return S_OK;
}

STDMETHODIMP PhoneCamPin::QueryAccept(const AM_MEDIA_TYPE* pmt) {
    return IsAcceptableFormat(pmt) ? S_OK : S_FALSE;
}

STDMETHODIMP PhoneCamPin::EnumMediaTypes(IEnumMediaTypes** ppEnum) {
    if (!ppEnum) return E_POINTER;
    /* Qualified: inside this method the unqualified name would find the method
     * itself rather than the class. */
    ::EnumMediaTypes* e = new ::EnumMediaTypes();
    if (!e) return E_OUTOFMEMORY;
    *ppEnum = e;
    return S_OK;
}

STDMETHODIMP PhoneCamPin::QueryInternalConnections(IPin** apPin, ULONG* nPin) {
    (void)apPin;
    (void)nPin;
    return E_NOTIMPL; /* single output pin: nothing to report */
}

STDMETHODIMP PhoneCamPin::EndOfStream() { return S_OK; }

STDMETHODIMP PhoneCamPin::BeginFlush() { return S_OK; }

STDMETHODIMP PhoneCamPin::EndFlush() { return S_OK; }

STDMETHODIMP PhoneCamPin::NewSegment(REFERENCE_TIME tStart, REFERENCE_TIME tStop,
                                     double dRate) {
    (void)tStart;
    (void)tStop;
    (void)dRate;
    return S_OK;
}

STDMETHODIMP PhoneCamPin::SetFormat(AM_MEDIA_TYPE* pmt) {
    if (!pmt) return E_POINTER;
    if (!IsAcceptableFormat(pmt)) return VFW_E_TYPE_NOT_ACCEPTED;
    if (m_pDownstream) return VFW_E_ALREADY_CONNECTED; /* must be set before Connect */

    ParseFormat(pmt, &m_width, &m_height, &m_fps, &m_nv12);
    return S_OK;
}

STDMETHODIMP PhoneCamPin::GetFormat(AM_MEDIA_TYPE** ppmt) {
    if (!ppmt) return E_POINTER;
    AM_MEDIA_TYPE* pmt = (AM_MEDIA_TYPE*)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
    if (!pmt) return E_OUTOFMEMORY;
    ZeroMemory(pmt, sizeof(*pmt));
    FillVideoInfoHeader(pmt, m_width, m_height, m_fps, m_nv12);
    if (!pmt->pbFormat) {
        CoTaskMemFree(pmt);
        return E_OUTOFMEMORY;
    }
    *ppmt = pmt;
    return S_OK;
}

STDMETHODIMP PhoneCamPin::GetNumberOfCapabilities(int* piCount, int* piSize) {
    if (!piCount || !piSize) return E_POINTER;
    *piCount = kCapCount;
    *piSize = sizeof(VIDEO_STREAM_CONFIG_CAPS);
    return S_OK;
}

STDMETHODIMP PhoneCamPin::GetStreamCaps(int iIndex, AM_MEDIA_TYPE** ppmt,
                                        BYTE* pSCC) {
    if (!ppmt || !pSCC) return E_POINTER;
    if (iIndex < 0 || iIndex >= kCapCount) return S_FALSE;

    // IAMStreamConfig specifies an out pointer: never read its initial value.
    *ppmt = NULL;
    AM_MEDIA_TYPE* pmt = (AM_MEDIA_TYPE*)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
    if (!pmt) return E_OUTOFMEMORY;
    ZeroMemory(pmt, sizeof(*pmt));
    const Cap& cap = kCaps[iIndex];
    FillVideoInfoHeader(pmt, cap.w, cap.h, cap.fps, cap.nv12);
    if (!pmt->pbFormat) {
        CoTaskMemFree(pmt);
        *ppmt = NULL;
        return E_OUTOFMEMORY;
    }
    *ppmt = pmt;

    VIDEO_STREAM_CONFIG_CAPS* caps = (VIDEO_STREAM_CONFIG_CAPS*)pSCC;
    ZeroMemory(caps, sizeof(*caps));
    caps->guid = FORMAT_VideoInfo;
    caps->VideoStandard = 0;
    caps->InputSize.cx = kCaps[iIndex].w;
    caps->InputSize.cy = kCaps[iIndex].h;
    caps->MinCroppingSize.cx = kCaps[iIndex].w;
    caps->MinCroppingSize.cy = kCaps[iIndex].h;
    caps->MaxCroppingSize.cx = kCaps[iIndex].w;
    caps->MaxCroppingSize.cy = kCaps[iIndex].h;
    caps->CropGranularityX = 1;
    caps->CropGranularityY = 1;
    caps->MinOutputSize.cx = kCaps[iIndex].w;
    caps->MinOutputSize.cy = kCaps[iIndex].h;
    caps->MaxOutputSize.cx = kCaps[iIndex].w;
    caps->MaxOutputSize.cy = kCaps[iIndex].h;
    caps->OutputGranularityX = 1;
    caps->OutputGranularityY = 1;
    caps->MinFrameInterval = 10000000LL / cap.fps;
    caps->MaxFrameInterval = 10000000LL / cap.fps;
    caps->MinBitsPerSecond = (LONG)((UINT64)FrameBytes(cap.w, cap.h, cap.nv12) * 8 * cap.fps / 4);
    caps->MaxBitsPerSecond = (LONG)((UINT64)FrameBytes(cap.w, cap.h, cap.nv12) * 8 * cap.fps);
    return S_OK;
}

STDMETHODIMP PhoneCamPin::Set(REFGUID, DWORD, LPVOID, DWORD, LPVOID, DWORD) {
    return E_NOTIMPL;
}

STDMETHODIMP PhoneCamPin::Get(REFGUID guidPropSet, DWORD dwPropID, LPVOID,
                              DWORD, LPVOID pPropData, DWORD cbPropData,
                              DWORD* pcbReturned) {
    /* Pins that do not answer AMPROPERTY_PIN_CATEGORY make some apps assume
     * "not a capture pin" and skip the device entirely. */
    if (guidPropSet == AMPROPSETID_Pin && dwPropID == AMPROPERTY_PIN_CATEGORY) {
        if (!pPropData || !pcbReturned) return E_POINTER;
        if (cbPropData < sizeof(GUID)) return E_UNEXPECTED;
        *(GUID*)pPropData = PIN_CATEGORY_CAPTURE;
        *pcbReturned = sizeof(GUID);
        return S_OK;
    }
    return E_PROP_ID_UNSUPPORTED;
}

STDMETHODIMP PhoneCamPin::QuerySupported(REFGUID guidPropSet, DWORD dwPropID,
                                         DWORD* pTypeSupport) {
    if (guidPropSet == AMPROPSETID_Pin && dwPropID == AMPROPERTY_PIN_CATEGORY) {
        if (!pTypeSupport) return E_POINTER;
        *pTypeSupport = KSPROPERTY_SUPPORT_GET;
        return S_OK;
    }
    return E_PROP_SET_UNSUPPORTED;
}

/* ------------------------------------------------------------------ */
/* Shared memory                                                       */
/* ------------------------------------------------------------------ */

bool PhoneCamPin::OpenSharedMemory() {
    if (m_pView) return true;
    m_hMap = OpenFileMappingW(FILE_MAP_READ, FALSE, PHONECAM_SHM_NAME);
    if (!m_hMap) return false;
    m_pView = (BYTE*)MapViewOfFile(m_hMap, FILE_MAP_READ, 0, 0, (SIZE_T)PHONECAM_SHM_SIZE);
    if (!m_pView) {
        CloseHandle(m_hMap);
        m_hMap = NULL;
        return false;
    }
    return true;
}

void PhoneCamPin::CloseSharedMemory() {
    if (m_pView) {
        UnmapViewOfFile(m_pView);
        m_pView = NULL;
    }
    if (m_hMap) {
        CloseHandle(m_hMap);
        m_hMap = NULL;
    }
}

bool PhoneCamPin::CopyLatestFrame(BYTE* dst, UINT32* index, INT64* ptsUs, UINT* fps) {
    if (!m_pView) return false;
    const PhoneCamHeader* hdr = (const PhoneCamHeader*)m_pView;
    if (hdr->magic != PHONECAM_MAGIC || hdr->version != PHONECAM_VERSION || hdr->format != PHONECAM_FMT_NV12) return false;

    for (int attempt = 0; attempt < 4; ++attempt) {
        const UINT32 before = hdr->frameIndex;
        if (!before) return false;
        if (before & 1u) {
            YieldProcessor();
            continue;
        }
        MemoryBarrier();
        const UINT w = hdr->width, h = hdr->height, stride = hdr->stride;
        const UINT32 which = hdr->activeBuffer;
        if (!SizeOk(w, h) || stride != w || which >= PHONECAM_BUFFERS) return false;
        /* The producer can disappear while readers keep the mapping alive; a
         * heartbeat avoids showing a frozen face indefinitely. */
        if ((DWORD)(GetTickCount() - hdr->heartbeat) > 2500) return false;
        const INT64 pts = hdr->ptsUs;
        const UINT srcFps = hdr->fps;
        const BYTE* src = m_pView + PHONECAM_BUF_OFFSET(which);
        if (m_nv12) ScaleNv12(src, w, h, dst, m_width, m_height);
        else Nv12ToRgb32(src, w, h, dst, m_width, m_height, m_bottomUp);
        MemoryBarrier();
        if (hdr->frameIndex == before) {
            *index = before;
            *ptsUs = pts;
            *fps = srcFps ? srcFps : m_fps;
            return true;
        }
    }
    return false; /* never publish a copy known to have raced the producer */
}

/* ------------------------------------------------------------------ */
/* Streaming                                                           */
/* ------------------------------------------------------------------ */

void PhoneCamPin::StartStreaming() {
    if (m_hThread) return;
    if (!m_pAllocator || !m_pDownstreamInput) return;
    m_pAllocator->Commit();
    ResetEvent(m_hStop);
    m_lastIndex = 0;
    m_haveBase = false;
    m_rtLast = -1;
    QueryPerformanceCounter(&m_qpcStart);
    m_hThread = CreateThread(NULL, 0, &PhoneCamPin::ThreadEntry, this, 0, NULL);
}

void PhoneCamPin::StopStreaming() {
    if (!m_hThread) return;
    SetEvent(m_hStop);
    // Wake blocking allocator/Receive calls before joining. Never destroy a
    // pin while its worker still has a raw pointer to it.
    if (m_pDownstream) m_pDownstream->BeginFlush();
    if (m_pAllocator) m_pAllocator->Decommit();
    WaitForSingleObject(m_hThread, INFINITE);
    if (m_pDownstream) m_pDownstream->EndFlush();
    CloseHandle(m_hThread);
    m_hThread = NULL;
}

DWORD WINAPI PhoneCamPin::ThreadEntry(LPVOID param) {
    ((PhoneCamPin*)param)->StreamLoop();
    return 0;
}

/* Current stream time: graph clock relative to Run() when running, else a
 * private monotonic clock. */
REFERENCE_TIME PhoneCamPin::StreamTime() {
    IReferenceClock* clock = m_pFilter->Clock();
    REFERENCE_TIME now = 0;
    if (clock && m_pFilter->State() == State_Running && SUCCEEDED(clock->GetTime(&now))) return now - m_tStart;
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (REFERENCE_TIME)((c.QuadPart - m_qpcStart.QuadPart) * 10000000.0 / f.QuadPart);
}

/* Frames are delivered as soon as the producer publishes them, not on a timer
 * of our own: a fixed 30 Hz clock beating against the phone's own clock is
 * what used to duplicate and drop frames in a regular pattern. A 1 ms
 * high-resolution timer keeps the check cheap. */
void PhoneCamPin::StreamLoop() {
    HANDLE timer = CreateWaitableTimerExW(NULL, NULL, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
    if (!timer) timer = CreateWaitableTimerW(NULL, FALSE, NULL);
    HANDLE handles[2] = {m_hStop, timer};
    DWORD lastDelivery = GetTickCount();
    for (;;) {
        LARGE_INTEGER due;
        due.QuadPart = -10000; /* 1 ms */
        SetWaitableTimer(timer, &due, 0, NULL, NULL, FALSE);
        if (WaitForMultipleObjects(2, handles, FALSE, INFINITE) == WAIT_OBJECT_0) break;

        if (!m_pView) {
            const DWORD now = GetTickCount();
            if (now - m_lastOpenTry >= 1000) {
                m_lastOpenTry = now;
                OpenSharedMemory();
            }
        }
        const PhoneCamHeader* hdr = m_pView ? (const PhoneCamHeader*)m_pView : NULL;
        const UINT32 index = hdr ? hdr->frameIndex : 0;
        const bool fresh = index != 0 && !(index & 1u) && index != m_lastIndex;
        /* Without new frames, keep the app alive with a slow trickle of the
         * "no signal" picture instead of going silent. */
        if (!fresh && GetTickCount() - lastDelivery < 200) continue;
        DeliverOneFrame();
        lastDelivery = GetTickCount();
    }
    CloseHandle(timer);
}

void PhoneCamPin::DeliverOneFrame() {
    if (!m_pAllocator || !m_pDownstreamInput) return;

    IMediaSample* pSample = NULL;
    if (FAILED(m_pAllocator->GetBuffer(&pSample, NULL, NULL, 0))) return;

    BYTE* dst = NULL;
    if (SUCCEEDED(pSample->GetPointer(&dst))) {
        UINT32 index = 0;
        INT64 pts = 0;
        UINT fps = m_fps;
        const bool live = CopyLatestFrame(dst, &index, &pts, &fps);
        if (!live) {
            /* An app handed no frames at all sits on a black preview and looks
             * hung, which is a worse answer than "no signal". */
            FillNoSignal(dst, m_width, m_height, m_nv12);
            m_haveBase = false;
        }
        m_lastIndex = live ? index : 0;

        const REFERENCE_TIME now = StreamTime();
        REFERENCE_TIME start = now;
        if (live) {
            REFERENCE_TIME mapped = m_rtBase + (pts - m_ptsBase) * 10;
            /* Rebase when the phone clock and ours drift apart, after a gap, or
             * when the phone restarts its stream. */
            if (!m_haveBase || mapped < now - 1500000 || mapped > now + 500000) {
                m_haveBase = true;
                m_rtBase = now;
                m_ptsBase = pts;
                mapped = now;
            }
            start = mapped;
        }
        if (start <= m_rtLast) start = m_rtLast + 1;
        m_rtLast = start;
        REFERENCE_TIME stop = start + 10000000LL / (fps ? fps : 30);

        pSample->SetActualDataLength((long)FrameBytes(m_width, m_height, m_nv12));
        pSample->SetSyncPoint(TRUE);
        pSample->SetPreroll(FALSE);
        pSample->SetTime(&start, &stop);
        m_pDownstreamInput->Receive(pSample);
    }
    pSample->Release();
}

/* ------------------------------------------------------------------ */
/* PhoneCamFilter implementation                                       */
/* ------------------------------------------------------------------ */

PhoneCamFilter::PhoneCamFilter(HRESULT* phr)
    : m_ref(1), m_pPin(NULL), m_pGraph(NULL), m_pClock(NULL), m_state(State_Stopped) {
    InterlockedIncrement(&g_cLockedObjects);
    m_pPin = new PhoneCamPin(this, phr);
    if (!m_pPin && phr) *phr = E_OUTOFMEMORY;
}

PhoneCamFilter::~PhoneCamFilter() {
    if (m_pClock) m_pClock->Release();
    delete m_pPin;
    InterlockedDecrement(&g_cLockedObjects);
}

STDMETHODIMP PhoneCamFilter::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IPersist || riid == IID_IMediaFilter ||
        riid == IID_IBaseFilter) {
        *ppv = (IBaseFilter*)this;
        AddRef();
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) PhoneCamFilter::AddRef() {
    return (ULONG)InterlockedIncrement(&m_ref);
}

STDMETHODIMP_(ULONG) PhoneCamFilter::Release() {
    const LONG r = InterlockedDecrement(&m_ref);
    if (r == 0) delete this;
    return (ULONG)r;
}

STDMETHODIMP PhoneCamFilter::GetClassID(CLSID* pClsID) {
    if (!pClsID) return E_POINTER;
    *pClsID = CLSID_PhoneCamFilter;
    return S_OK;
}

STDMETHODIMP PhoneCamFilter::Stop() {
    if (m_pPin) m_pPin->StopStreaming();
    m_state = State_Stopped;
    return S_OK;
}

STDMETHODIMP PhoneCamFilter::Pause() {
    /* Live sources conventionally begin delivering in Pause so the renderer
     * has a frame ready the moment Run arrives. */
    if (m_pPin) m_pPin->StartStreaming();
    m_state = State_Paused;
    return S_OK;
}

STDMETHODIMP PhoneCamFilter::Run(REFERENCE_TIME tStart) {
    if (m_pPin) {
        m_pPin->m_tStart = tStart;
        m_pPin->StartStreaming();
    }
    m_state = State_Running;
    return S_OK;
}

STDMETHODIMP PhoneCamFilter::GetState(DWORD dwMilliSecsTimeout, FILTER_STATE* pState) {
    (void)dwMilliSecsTimeout;
    if (!pState) return E_POINTER;
    *pState = m_state;
    return m_state == State_Paused ? VFW_S_CANT_CUE : S_OK;
}

STDMETHODIMP PhoneCamFilter::SetSyncSource(IReferenceClock* pClock) {
    if (m_pClock) m_pClock->Release();
    m_pClock = pClock;
    if (m_pClock) m_pClock->AddRef();
    return S_OK;
}

STDMETHODIMP PhoneCamFilter::GetSyncSource(IReferenceClock** ppClock) {
    if (!ppClock) return E_POINTER;
    *ppClock = m_pClock;
    if (m_pClock) m_pClock->AddRef();
    return S_OK;
}

STDMETHODIMP PhoneCamFilter::EnumPins(IEnumPins** ppEnum) {
    if (!ppEnum) return E_POINTER;
    /* Qualified for the same reason as EnumMediaTypes above. */
    ::EnumPins* e = new ::EnumPins(m_pPin);
    if (!e) return E_OUTOFMEMORY;
    *ppEnum = e;
    return S_OK;
}

STDMETHODIMP PhoneCamFilter::FindPin(LPCWSTR Id, IPin** ppPin) {
    if (!Id || !ppPin) return E_POINTER;
    if (lstrcmpW(Id, kPinName) == 0) {
        *ppPin = m_pPin;
        m_pPin->AddRef();
        return S_OK;
    }
    *ppPin = NULL;
    return VFW_E_NOT_FOUND;
}

STDMETHODIMP PhoneCamFilter::QueryFilterInfo(FILTER_INFO* pInfo) {
    if (!pInfo) return E_POINTER;
    lstrcpynW(pInfo->achName, kFilterName, sizeof(pInfo->achName) / sizeof(WCHAR));
    pInfo->pGraph = m_pGraph;
    if (m_pGraph) m_pGraph->AddRef();
    return S_OK;
}

STDMETHODIMP PhoneCamFilter::JoinFilterGraph(IFilterGraph* pGraph, LPCWSTR pName) {
    (void)pName;
    // The graph owns the filter; a reverse strong reference would leak both.
    m_pGraph = pGraph;
    return S_OK;
}

STDMETHODIMP PhoneCamFilter::QueryVendorInfo(LPWSTR* pVendorInfo) {
    if (!pVendorInfo) return E_POINTER;
    *pVendorInfo = NULL;
    return E_NOTIMPL;
}

/* ------------------------------------------------------------------ */
/* Class factory                                                       */
/* ------------------------------------------------------------------ */

class PhoneCamClassFactory : public IClassFactory {
  public:
    PhoneCamClassFactory() : m_ref(1) { InterlockedIncrement(&g_cLockedObjects); }
    ~PhoneCamClassFactory() { InterlockedDecrement(&g_cLockedObjects); }

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = (IClassFactory*)this;
            AddRef();
            return S_OK;
        }
        *ppv = NULL;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() { return (ULONG)InterlockedIncrement(&m_ref); }
    STDMETHODIMP_(ULONG) Release() {
        const LONG r = InterlockedDecrement(&m_ref);
        if (r == 0) delete this;
        return (ULONG)r;
    }

    STDMETHODIMP CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) {
        if (!ppv) return E_POINTER;
        *ppv = NULL;
        if (pUnkOuter) return CLASS_E_NOAGGREGATION;
        HRESULT hr = S_OK;
        PhoneCamFilter* pFilter = new PhoneCamFilter(&hr);
        if (!pFilter) return E_OUTOFMEMORY;
        if (FAILED(hr)) { pFilter->Release(); return hr; }
        hr = pFilter->QueryInterface(riid, ppv);
        pFilter->Release();
        return hr;
    }

    STDMETHODIMP LockServer(BOOL bLock) {
        if (bLock) InterlockedIncrement(&g_cServerLocks); else InterlockedDecrement(&g_cServerLocks);
        return S_OK;
    }

  private:
    LONG m_ref;
};

/* ------------------------------------------------------------------ */
/* Registration                                                        */
/* ------------------------------------------------------------------ */



static HRESULT SetKeyValue(HKEY hKey, LPCWSTR sub, LPCWSTR name, LPCWSTR value) {
    HKEY hSub = NULL;
    LONG r = RegCreateKeyExW(hKey, sub, 0, NULL, 0, KEY_WRITE, NULL, &hSub, NULL);
    if (r != ERROR_SUCCESS) return HRESULT_FROM_WIN32(r);
    r = RegSetValueExW(hSub, name, 0, REG_SZ, (const BYTE*)value,
                       (DWORD)((lstrlenW(value) + 1) * sizeof(WCHAR)));
    RegCloseKey(hSub);
    return HRESULT_FROM_WIN32(r);
}

static void GuidToString(const GUID& g, wchar_t* out) {
    wsprintfW(out, L"{%08lX-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X}", g.Data1,
              g.Data2, g.Data3, g.Data4[0], g.Data4[1], g.Data4[2], g.Data4[3],
              g.Data4[4], g.Data4[5], g.Data4[6], g.Data4[7]);
}

STDAPI DllRegisterServer() {
    wchar_t szModule[MAX_PATH];
    if (!GetModuleFileNameW(g_hInst, szModule, MAX_PATH)) {
        return HRESULT_FROM_WIN32(GetLastError());
    }

    wchar_t szClsid[64];
    GuidToString(CLSID_PhoneCamFilter, szClsid);

    wchar_t szKey[256];
    HRESULT hr;

    wsprintfW(szKey, L"Software\\Classes\\CLSID\\%s", szClsid);
    hr = SetKeyValue(HKEY_CURRENT_USER, szKey, NULL, kFilterName);
    if (FAILED(hr)) return hr;

    wsprintfW(szKey, L"Software\\Classes\\CLSID\\%s\\InprocServer32", szClsid);
    hr = SetKeyValue(HKEY_CURRENT_USER, szKey, NULL, szModule);
    if (FAILED(hr)) return hr;
    hr = SetKeyValue(HKEY_CURRENT_USER, szKey, L"ThreadingModel", L"Both");
    if (FAILED(hr)) return hr;

    /* The Instance key is what makes it show up as a *device*. */
    wsprintfW(szKey, L"Software\\Classes\\CLSID\\%s\\Instance", szClsid);
    hr = SetKeyValue(HKEY_CURRENT_USER, szKey, L"CLSID", szClsid);
    if (FAILED(hr)) return hr;
    hr = SetKeyValue(HKEY_CURRENT_USER, szKey, L"FriendlyName", kFilterName);
    if (FAILED(hr)) return hr;

    /* ...and this one files it under "video input devices". */
    wchar_t szCategory[64];
    GuidToString(CLSID_VideoInputDeviceCategory, szCategory);
    wsprintfW(szKey, L"Software\\Classes\\CLSID\\%s\\Instance\\%s", szCategory, szClsid);
    hr = SetKeyValue(HKEY_CURRENT_USER, szKey, L"CLSID", szClsid);
    if (FAILED(hr)) return hr;
    hr = SetKeyValue(HKEY_CURRENT_USER, szKey, L"FriendlyName", kFilterName);
    if (FAILED(hr)) return hr;

    return S_OK;
}

STDAPI DllUnregisterServer() {
    wchar_t szClsid[64];
    GuidToString(CLSID_PhoneCamFilter, szClsid);

    wchar_t szCategory[64];
    GuidToString(CLSID_VideoInputDeviceCategory, szCategory);

    wchar_t szKey[256];
    wsprintfW(szKey, L"Software\\Classes\\CLSID\\%s\\Instance\\%s", szCategory, szClsid);
    RegDeleteKeyW(HKEY_CURRENT_USER, szKey);

    wsprintfW(szKey, L"Software\\Classes\\CLSID\\%s\\Instance", szClsid);
    RegDeleteKeyW(HKEY_CURRENT_USER, szKey);

    wsprintfW(szKey, L"Software\\Classes\\CLSID\\%s\\InprocServer32", szClsid);
    RegDeleteKeyW(HKEY_CURRENT_USER, szKey);

    wsprintfW(szKey, L"Software\\Classes\\CLSID\\%s", szClsid);
    RegDeleteKeyW(HKEY_CURRENT_USER, szKey);

    return S_OK;
}

/* ------------------------------------------------------------------ */
/* DLL entry points                                                    */
/* ------------------------------------------------------------------ */

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    if (rclsid != CLSID_PhoneCamFilter) return CLASS_E_CLASSNOTAVAILABLE;
    PhoneCamClassFactory* pFactory = new PhoneCamClassFactory();
    if (!pFactory) return E_OUTOFMEMORY;
    const HRESULT hr = pFactory->QueryInterface(riid, ppv);
    pFactory->Release();
    return hr;
}

STDAPI DllCanUnloadNow() {
    return (InterlockedCompareExchange(&g_cLockedObjects, 0, 0) == 0 && InterlockedCompareExchange(&g_cServerLocks, 0, 0) == 0) ? S_OK : S_FALSE;
}

BOOL WINAPI DllMain(HINSTANCE hInst, DWORD reason, LPVOID reserved) {
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        g_hInst = hInst;
        DisableThreadLibraryCalls(hInst);
    }
    return TRUE;
}
