use zune_core::{bytestream::ZCursor, colorspace::ColorSpace, options::DecoderOptions};
use zune_jpeg::JpegDecoder;
pub const W: usize = 1280;
pub const H: usize = 720;
pub fn decode(jpeg: &[u8], rotation: u16, out: &mut [u8]) -> Result<(usize, usize), String> {
    let options = DecoderOptions::default()
        .set_max_width(4096)
        .set_max_height(4096)
        .jpeg_set_max_scans(16)
        .jpeg_set_out_colorspace(ColorSpace::BGRA);
    let mut decoder = JpegDecoder::new_with_options(ZCursor::new(jpeg), options);
    decoder.decode_headers().map_err(|e| e.to_string())?;
    let (w, h) = decoder.dimensions().ok_or("Missing JPEG dimensions")?;
    if w == 0 || h == 0 || w * h > 8_500_000 {
        return Err("Image too large".into());
    }
    let pixels = decoder.decode().map_err(|e| e.to_string())?;
    if pixels.len() != w * h * 4 {
        return Err("Unexpected JPEG color format".into());
    }
    fit(&pixels, w, h, rotation, out, W, H);
    Ok((w, h))
}
// One pass combines rotation and aspect-preserving scaling. Common landscape
// 720p frames are copied directly, avoiding a per-pixel loop entirely.
pub fn fit(src: &[u8], sw: usize, sh: usize, rotation: u16, dst: &mut [u8], dw: usize, dh: usize) {
    if rotation == 0 && sw == dw && sh == dh {
        dst.copy_from_slice(src);
        return;
    }
    dst.fill(0);
    let (rw, rh) = if rotation == 90 || rotation == 270 {
        (sh, sw)
    } else {
        (sw, sh)
    };
    let scale = (dw as f64 / rw as f64).min(dh as f64 / rh as f64);
    let ow = ((rw as f64 * scale).round() as usize).clamp(1, dw);
    let oh = ((rh as f64 * scale).round() as usize).clamp(1, dh);
    let ox = (dw - ow) / 2;
    let oy = (dh - oh) / 2;
    for y in 0..oh {
        for x in 0..ow {
            let rx = (x * rw / ow).min(rw - 1);
            let ry = (y * rh / oh).min(rh - 1);
            let (sx, sy) = match rotation {
                90 => (ry, sh - 1 - rx),
                180 => (sw - 1 - rx, sh - 1 - ry),
                270 => (sw - 1 - ry, rx),
                _ => (rx, ry),
            };
            let a = (sy * sw + sx) * 4;
            let b = ((y + oy) * dw + x + ox) * 4;
            dst[b..b + 4].copy_from_slice(&src[a..a + 4]);
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rotations_preserve_corners() {
        let src: Vec<u8> = [1, 2, 3, 4, 5, 6]
            .iter()
            .flat_map(|v| [*v, 0, 0, 255])
            .collect();
        for (r, w, h, expected) in [
            (0, 3, 2, vec![1, 2, 3, 4, 5, 6]),
            (90, 2, 3, vec![4, 1, 5, 2, 6, 3]),
            (180, 3, 2, vec![6, 5, 4, 3, 2, 1]),
            (270, 2, 3, vec![3, 6, 2, 5, 1, 4]),
        ] {
            let mut dst = vec![0; 24];
            fit(&src, 3, 2, r, &mut dst, w, h);
            assert_eq!(dst.chunks(4).map(|p| p[0]).collect::<Vec<_>>(), expected);
        }
    }
    #[test]
    fn portrait_is_letterboxed() {
        let mut out = vec![42; 4 * 4 * 4];
        fit(&[255; 2 * 4 * 4], 2, 4, 0, &mut out, 4, 4);
        for row in out.chunks(16) {
            assert_eq!(&row[..4], &[0; 4]);
            assert_eq!(&row[4..12], &[255; 8]);
            assert_eq!(&row[12..], &[0; 4]);
        }
    }
    #[test]
    fn rejects_bad_jpeg() {
        assert!(decode(&[0; 100], 0, &mut vec![0; W * H * 4]).is_err());
    }
}
