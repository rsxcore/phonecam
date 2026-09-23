//! H.264 / HEVC decoding through the Media Foundation decoder MFTs that ship
//! with Windows. Output is NV12, which is also what the virtual camera hands
//! to apps, so no colour conversion happens on the hot path.

use std::mem::ManuallyDrop;

use windows::core::{Interface, GUID};
use windows::Win32::Foundation::HMODULE;
use windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_HARDWARE;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::Common::DXGI_SAMPLE_DESC;
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::{CoInitializeEx, CoTaskMemFree, COINIT_MULTITHREADED};

use crate::proto::Codec;

/// One decoded picture, tightly packed NV12 (`stride == width`).
pub struct Picture {
    pub width: u32,
    pub height: u32,
    pub pts_us: i64,
    pub data: Vec<u8>,
}

/// Hardware decoding: the MFT decodes into D3D11 textures on the GPU and we
/// read the NV12 planes back through a staging texture.
struct Gpu {
    context: ID3D11DeviceContext,
    device: ID3D11Device,
    _manager: IMFDXGIDeviceManager,
    staging: Option<(ID3D11Texture2D, u32, u32)>,
}

unsafe fn create_gpu() -> windows::core::Result<Gpu> {
    let mut device = None;
    let mut context = None;
    D3D11CreateDevice(
        None,
        D3D_DRIVER_TYPE_HARDWARE,
        HMODULE::default(),
        D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        None,
        D3D11_SDK_VERSION,
        Some(&mut device),
        None,
        Some(&mut context),
    )?;
    let device: ID3D11Device = device.unwrap();
    // The decoder uses the device from its own threads.
    let _ = device.cast::<ID3D11Multithread>()?.SetMultithreadProtected(true);
    let mut token = 0u32;
    let mut manager = None;
    MFCreateDXGIDeviceManager(&mut token, &mut manager)?;
    let manager = manager.unwrap();
    manager.ResetDevice(&device, token)?;
    Ok(Gpu { context: context.unwrap(), device, _manager: manager, staging: None })
}

pub struct Decoder {
    transform: IMFTransform,
    gpu: Option<Gpu>,
    /// Visible size, from the phone's CONFIG message.
    width: u32,
    height: u32,
    /// Layout of the decoder's output buffers, which may be padded (1088 rows).
    out_stride: u32,
    out_rows: u32,
    provides_samples: bool,
    out_buffer_size: u32,
    pub name: String,
}

/// Call once per thread that uses a decoder.
pub fn init_thread() -> Result<(), String> {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET).map_err(|e| format!("Media Foundation unavailable: {e}"))
    }
}

pub fn supports(codec: Codec) -> bool {
    unsafe { find(codec).map(|v| !v.is_empty()).unwrap_or(false) }
}

unsafe fn find(codec: Codec) -> windows::core::Result<Vec<IMFActivate>> {
    let input = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: subtype(codec) };
    let output = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: MFVideoFormat_NV12 };
    let mut list: *mut Option<IMFActivate> = std::ptr::null_mut();
    let mut count = 0u32;
    MFTEnumEx(
        MFT_CATEGORY_VIDEO_DECODER,
        MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER,
        Some(&input),
        Some(&output),
        &mut list,
        &mut count,
    )?;
    let mut result = Vec::new();
    for i in 0..count as usize {
        if let Some(a) = (*list.add(i)).take() {
            result.push(a);
        }
    }
    if !list.is_null() {
        CoTaskMemFree(Some(list as *const _));
    }
    Ok(result)
}

fn subtype(codec: Codec) -> GUID {
    match codec {
        Codec::H264 => MFVideoFormat_H264,
        Codec::Hevc => MFVideoFormat_HEVC,
    }
}

fn pack(hi: u32, lo: u32) -> u64 {
    ((hi as u64) << 32) | lo as u64
}

impl Decoder {
    pub fn new(codec: Codec, width: u32, height: u32, fps: u32) -> Result<Self, String> {
        unsafe { Self::create(codec, width, height, fps) }.map_err(|e| format!("Decoder: {e}"))
    }

    unsafe fn create(codec: Codec, width: u32, height: u32, fps: u32) -> windows::core::Result<Self> {
        let activates = find(codec)?;
        let activate = activates
            .first()
            .ok_or_else(|| windows::core::Error::new(MF_E_TOPO_CODEC_NOT_FOUND, "no decoder installed for this codec"))?;
        let mut raw = windows::core::PWSTR::null();
        let mut len = 0u32;
        let name = if activate.GetAllocatedString(&MFT_FRIENDLY_NAME_Attribute, &mut raw, &mut len).is_ok() {
            let s = raw.to_string().unwrap_or_default();
            CoTaskMemFree(Some(raw.0 as *const _));
            s
        } else {
            "Media Foundation".into()
        };
        let transform: IMFTransform = activate.ActivateObject()?;

        // Without this the decoder holds several frames back for reordering,
        // which the phone never uses (no B-frames): pure added latency.
        let mut gpu = None;
        if let Ok(attrs) = transform.GetAttributes() {
            let _ = attrs.SetUINT32(&MF_LOW_LATENCY, 1);
            if attrs.GetUINT32(&MF_SA_D3D11_AWARE).unwrap_or(0) != 0 && std::env::var_os("PHONECAM_SOFTWARE_DECODE").is_none() {
                if let Ok(g) = create_gpu() {
                    let manager: &IMFDXGIDeviceManager = &g._manager;
                    if transform.ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, manager.as_raw() as usize).is_ok() {
                        gpu = Some(g);
                    }
                }
            }
        }
        let name = if gpu.is_some() { format!("{name} (GPU)") } else { format!("{name} (CPU)") };

        let input = MFCreateMediaType()?;
        input.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
        input.SetGUID(&MF_MT_SUBTYPE, &subtype(codec))?;
        input.SetUINT64(&MF_MT_FRAME_SIZE, pack(width, height))?;
        input.SetUINT64(&MF_MT_FRAME_RATE, pack(fps, 1))?;
        input.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
        transform.SetInputType(0, &input, 0)?;

        let mut decoder = Self {
            transform,
            gpu,
            width,
            height,
            out_stride: width,
            out_rows: height,
            provides_samples: false,
            out_buffer_size: 0,
            name,
        };
        decoder.choose_output()?;
        decoder.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
        decoder.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)?;
        Ok(decoder)
    }

    unsafe fn choose_output(&mut self) -> windows::core::Result<()> {
        let mut index = 0;
        loop {
            let t = self.transform.GetOutputAvailableType(0, index)?;
            if t.GetGUID(&MF_MT_SUBTYPE)? == MFVideoFormat_NV12 {
                self.transform.SetOutputType(0, &t, 0)?;
                let size = t.GetUINT64(&MF_MT_FRAME_SIZE).unwrap_or(pack(self.width, self.height));
                let (w, h) = ((size >> 32) as u32, size as u32);
                self.out_stride = t.GetUINT32(&MF_MT_DEFAULT_STRIDE).map(|s| s as i32 as u32).unwrap_or(w).max(w);
                self.out_rows = h.max(self.height);
                let info = self.transform.GetOutputStreamInfo(0)?;
                self.provides_samples = info.dwFlags
                    & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32 | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0 as u32)
                    != 0;
                self.out_buffer_size = info.cbSize.max(self.out_stride * self.out_rows * 3 / 2);
                return Ok(());
            }
            index += 1;
        }
    }

    /// Feeds one access unit and returns every picture that became ready.
    pub fn decode(&mut self, data: &[u8], pts_us: i64) -> Result<Vec<Picture>, String> {
        unsafe { self.decode_inner(data, pts_us) }.map_err(|e| format!("Decode failed: {e}"))
    }

    unsafe fn decode_inner(&mut self, data: &[u8], pts_us: i64) -> windows::core::Result<Vec<Picture>> {
        let buffer = MFCreateMemoryBuffer(data.len() as u32)?;
        let mut ptr = std::ptr::null_mut();
        buffer.Lock(&mut ptr, None, None)?;
        std::ptr::copy_nonoverlapping(data.as_ptr(), ptr, data.len());
        buffer.Unlock()?;
        buffer.SetCurrentLength(data.len() as u32)?;
        let sample = MFCreateSample()?;
        sample.AddBuffer(&buffer)?;
        sample.SetSampleTime(pts_us * 10)?;

        let mut pictures = Vec::new();
        if let Err(e) = self.transform.ProcessInput(0, &sample, 0) {
            if e.code() != MF_E_NOTACCEPTING {
                return Err(e);
            }
            self.drain(&mut pictures)?;
            self.transform.ProcessInput(0, &sample, 0)?;
        }
        self.drain(&mut pictures)?;
        Ok(pictures)
    }

    unsafe fn drain(&mut self, out: &mut Vec<Picture>) -> windows::core::Result<()> {
        loop {
            let provided = if self.provides_samples {
                None
            } else {
                let s = MFCreateSample()?;
                s.AddBuffer(&MFCreateMemoryBuffer(self.out_buffer_size)?)?;
                Some(s)
            };
            let mut buffers = [MFT_OUTPUT_DATA_BUFFER {
                dwStreamID: 0,
                pSample: ManuallyDrop::new(provided),
                dwStatus: 0,
                pEvents: ManuallyDrop::new(None),
            }];
            let mut status = 0u32;
            let result = self.transform.ProcessOutput(0, &mut buffers, &mut status);
            let sample = ManuallyDrop::take(&mut buffers[0].pSample);
            drop(ManuallyDrop::take(&mut buffers[0].pEvents));
            match result {
                Ok(()) => {
                    if let Some(sample) = sample {
                        out.push(self.read_picture(&sample)?);
                    }
                }
                Err(e) if e.code() == MF_E_TRANSFORM_NEED_MORE_INPUT => return Ok(()),
                Err(e) if e.code() == MF_E_TRANSFORM_STREAM_CHANGE => self.choose_output()?,
                Err(e) => return Err(e),
            }
        }
    }

    unsafe fn read_picture(&mut self, sample: &IMFSample) -> windows::core::Result<Picture> {
        let pts = sample.GetSampleTime().unwrap_or(0) / 10;
        let (w, h) = (self.width as usize, self.height as usize);
        let mut data = vec![0u8; w * h * 3 / 2];

        if self.gpu.is_some() {
            let buffer = sample.GetBufferByIndex(0)?;
            if let Ok(dxgi) = buffer.cast::<IMFDXGIBuffer>() {
                self.read_texture(&dxgi, w, h, &mut data)?;
                return Ok(Picture { width: self.width, height: self.height, pts_us: pts, data });
            }
        }

        let buffer = sample.ConvertToContiguousBuffer()?;

        // Prefer the 2D interface: it reports the real pitch of padded buffers.
        if let Ok(b2d) = buffer.cast::<IMF2DBuffer>() {
            let mut scan0 = std::ptr::null_mut();
            let mut pitch = 0i32;
            b2d.Lock2D(&mut scan0, &mut pitch)?;
            copy_nv12(scan0, pitch as usize, self.out_rows as usize, w, h, &mut data);
            b2d.Unlock2D()?;
        } else {
            let mut ptr = std::ptr::null_mut();
            buffer.Lock(&mut ptr, None, None)?;
            copy_nv12(ptr, self.out_stride as usize, self.out_rows as usize, w, h, &mut data);
            buffer.Unlock()?;
        }
        Ok(Picture { width: self.width, height: self.height, pts_us: pts, data })
    }
}

impl Decoder {
    unsafe fn read_texture(&mut self, dxgi: &IMFDXGIBuffer, w: usize, h: usize, out: &mut [u8]) -> windows::core::Result<()> {
        let gpu = self.gpu.as_mut().unwrap();
        let mut raw = std::ptr::null_mut();
        dxgi.GetResource(&ID3D11Texture2D::IID, &mut raw)?;
        let texture = ID3D11Texture2D::from_raw(raw);
        let index = dxgi.GetSubresourceIndex()?;
        let mut desc = D3D11_TEXTURE2D_DESC::default();
        texture.GetDesc(&mut desc);

        let reuse = matches!(&gpu.staging, Some((_, sw, sh)) if *sw == desc.Width && *sh == desc.Height);
        if !reuse {
            let staging_desc = D3D11_TEXTURE2D_DESC {
                Width: desc.Width,
                Height: desc.Height,
                MipLevels: 1,
                ArraySize: 1,
                Format: desc.Format,
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                Usage: D3D11_USAGE_STAGING,
                BindFlags: 0,
                CPUAccessFlags: D3D11_CPU_ACCESS_READ.0 as u32,
                MiscFlags: 0,
            };
            let mut staging = None;
            gpu.device.CreateTexture2D(&staging_desc, None, Some(&mut staging))?;
            gpu.staging = Some((staging.unwrap(), desc.Width, desc.Height));
        }
        let staging = &gpu.staging.as_ref().unwrap().0;
        gpu.context.CopySubresourceRegion(staging, 0, 0, 0, 0, &texture, index, None);
        let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
        gpu.context.Map(staging, 0, D3D11_MAP_READ, 0, Some(&mut mapped))?;
        copy_nv12(mapped.pData as *const u8, mapped.RowPitch as usize, desc.Height as usize, w, h, out);
        gpu.context.Unmap(staging, 0);
        Ok(())
    }
}

/// Crops a (possibly padded) NV12 buffer into a tightly packed one.
unsafe fn copy_nv12(src: *const u8, pitch: usize, rows: usize, w: usize, h: usize, dst: &mut [u8]) {
    for y in 0..h {
        std::ptr::copy_nonoverlapping(src.add(y * pitch), dst.as_mut_ptr().add(y * w), w);
    }
    let uv_src = src.add(pitch * rows);
    let uv_dst = dst.as_mut_ptr().add(w * h);
    for y in 0..h / 2 {
        std::ptr::copy_nonoverlapping(uv_src.add(y * pitch), uv_dst.add(y * w), w);
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe {
            let _ = self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            let _ = self.transform.ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
        }
    }
}
