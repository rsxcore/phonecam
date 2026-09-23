//! NV12 helpers: rotation into upright orientation and a test pattern.

/// Rotates a tightly packed NV12 image clockwise by `rotation` degrees.
/// Returns the new `(width, height)`; `dst` is resized as needed.
pub fn rotate(src: &[u8], w: usize, h: usize, rotation: u16, dst: &mut Vec<u8>) -> (usize, usize) {
    let (dw, dh) = if rotation % 180 == 90 { (h, w) } else { (w, h) };
    dst.resize(w * h * 3 / 2, 0);
    let (y_src, uv_src) = src.split_at(w * h);
    let (y_dst, uv_dst) = dst.split_at_mut(w * h);
    rotate_plane::<1>(y_src, w, h, rotation, y_dst);
    rotate_plane::<2>(uv_src, w / 2, h / 2, rotation, uv_dst);
    (dw, dh)
}

/// Rotates one plane of `B`-byte pixels. Works in square tiles so both the
/// reads and the writes stay within a few cache lines.
fn rotate_plane<const B: usize>(src: &[u8], w: usize, h: usize, rotation: u16, dst: &mut [u8]) {
    const TILE: usize = 32;
    let dw = if rotation % 180 == 90 { h } else { w };
    if rotation == 0 {
        dst[..w * h * B].copy_from_slice(&src[..w * h * B]);
        return;
    }
    for ty in (0..h).step_by(TILE) {
        for tx in (0..w).step_by(TILE) {
            for y in ty..(ty + TILE).min(h) {
                let row = &src[y * w * B..(y + 1) * w * B];
                for x in tx..(tx + TILE).min(w) {
                    // Destination of source pixel (x, y) after a clockwise turn.
                    let (nx, ny) = match rotation {
                        90 => (h - 1 - y, x),
                        180 => (w - 1 - x, h - 1 - y),
                        _ => (y, w - 1 - x),
                    };
                    let d = (ny * dw + nx) * B;
                    dst[d..d + B].copy_from_slice(&row[x * B..x * B + B]);
                }
            }
        }
    }
}

/// Colour bars, a sweeping band and an orientation marker, in NV12.
pub fn test_pattern(buf: &mut Vec<u8>, w: usize, h: usize, phase: f32) {
    // BT.709 limited-range YUV for white, yellow, cyan, green, magenta, red, blue, black.
    const BARS: [(u8, u8, u8); 8] = [
        (235, 128, 128), (219, 16, 138), (188, 154, 16), (173, 42, 26),
        (78, 214, 230), (63, 102, 240), (32, 240, 118), (16, 128, 128),
    ];
    buf.resize(w * h * 3 / 2, 0);
    let sweep = ((phase * w as f32) as usize) % w;
    let band = ((phase * 0.37).fract() * h as f32) as usize;
    let (yp, uvp) = buf.split_at_mut(w * h);
    for y in 0..h {
        for x in 0..w {
            let (mut yy, _, _) = BARS[(x * 8 / w).min(7)];
            if x.abs_diff(sweep) < 40 || (y >= band && y < band + 24) {
                yy = 235;
            }
            if x < 96 && y < 96 && x >= 32 && y >= 32 {
                yy = 128; // grey square: must sit in the top-left corner
            }
            yp[y * w + x] = yy;
        }
    }
    for y in 0..h / 2 {
        for x in 0..w / 2 {
            let (_, u, v) = BARS[(x * 2 * 8 / w).min(7)];
            let o = y * w + x * 2;
            uvp[o] = u;
            uvp[o + 1] = v;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 4x2 image, Y values 1..=8, UV pairs (10,11) and (20,21).
    fn sample() -> Vec<u8> {
        vec![1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 20, 21]
    }

    #[test]
    fn rotations_move_corners_clockwise() {
        let mut out = Vec::new();
        assert_eq!(rotate(&sample(), 4, 2, 0, &mut out), (4, 2));
        assert_eq!(&out[..8], &[1, 2, 3, 4, 5, 6, 7, 8]);

        // Top-left of a clockwise turn is the old bottom-left.
        assert_eq!(rotate(&sample(), 4, 2, 90, &mut out), (2, 4));
        assert_eq!(&out[..8], &[5, 1, 6, 2, 7, 3, 8, 4]);
        assert_eq!(&out[8..], &[10, 11, 20, 21]);

        assert_eq!(rotate(&sample(), 4, 2, 180, &mut out), (4, 2));
        assert_eq!(&out[..8], &[8, 7, 6, 5, 4, 3, 2, 1]);
        assert_eq!(&out[8..], &[20, 21, 10, 11]);

        assert_eq!(rotate(&sample(), 4, 2, 270, &mut out), (2, 4));
        assert_eq!(&out[..8], &[4, 8, 3, 7, 2, 6, 1, 5]);
        assert_eq!(&out[8..], &[20, 21, 10, 11]);
    }

    #[test]
    fn large_rotation_round_trips() {
        let (w, h) = (1920, 1080);
        let src: Vec<u8> = (0..w * h * 3 / 2).map(|i| (i * 7 % 251) as u8).collect();
        let (mut a, mut b) = (Vec::new(), Vec::new());
        let (w1, h1) = rotate(&src, w, h, 90, &mut a);
        let (w2, h2) = rotate(&a, w1, h1, 270, &mut b);
        assert_eq!((w2, h2), (w, h));
        assert!(b == src);
    }
}
