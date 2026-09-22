//! Test and lab video source. [`Encoder`] writes a valid constrained-baseline H.264
//! stream with the two simplest macroblock kinds: every macroblock of an IDR is I_PCM
//! (raw samples), and a P picture codes changed macroblocks as I_PCM and skips the rest.
//! There is no prediction, transform, or entropy coding of samples, so the stream is
//! large but trivially correct, and any conforming decoder reproduces the canvas exactly.
//! [`Canvas`] draws rectangles and digits in YUV 4:2:0.
//!
//! This is not the product encoder (the app uses MediaCodec); it lets session tests and
//! the lab harness exercise real parameter sets, IDR recovery, and receiver decoding
//! without a platform codec.

/// A YUV 4:2:0 color.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Yuv(pub u8, pub u8, pub u8);

impl Yuv {
    /// BT.601 limited range from 8-bit RGB.
    pub fn from_rgb(r: u8, g: u8, b: u8) -> Self {
        let (r, g, b) = (i32::from(r), i32::from(g), i32::from(b));
        let y = (66 * r + 129 * g + 25 * b + 128) / 256 + 16;
        let u = (-38 * r - 74 * g + 112 * b + 128) / 256 + 128;
        let v = (112 * r - 94 * g - 18 * b + 128) / 256 + 128;
        Self(clamp(y), clamp(u), clamp(v))
    }
}

fn clamp(v: i32) -> u8 {
    v.clamp(1, 254) as u8
}

/// Planar YUV 4:2:0 picture whose size is padded to whole macroblocks.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Canvas {
    pub width: u32,
    pub height: u32,
    stride: usize,
    rows: usize,
    pub y: Vec<u8>,
    pub u: Vec<u8>,
    pub v: Vec<u8>,
}

/// 5x7 glyphs for 0-9, rows top to bottom, bit 4 leftmost.
const DIGITS: [[u8; 7]; 10] = [
    [0x0e, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0e],
    [0x04, 0x0c, 0x04, 0x04, 0x04, 0x04, 0x0e],
    [0x0e, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1f],
    [0x1f, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0e],
    [0x02, 0x06, 0x0a, 0x12, 0x1f, 0x02, 0x02],
    [0x1f, 0x10, 0x1e, 0x01, 0x01, 0x11, 0x0e],
    [0x06, 0x08, 0x10, 0x1e, 0x11, 0x11, 0x0e],
    [0x1f, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08],
    [0x0e, 0x11, 0x11, 0x0e, 0x11, 0x11, 0x0e],
    [0x0e, 0x11, 0x11, 0x0f, 0x01, 0x02, 0x0c],
];

impl Canvas {
    pub fn new(width: u32, height: u32, fill: Yuv) -> Self {
        let stride = width.div_ceil(16) as usize * 16;
        let rows = height.div_ceil(16) as usize * 16;
        Self {
            width,
            height,
            stride,
            rows,
            y: vec![fill.0; stride * rows],
            u: vec![fill.1; stride / 2 * rows / 2],
            v: vec![fill.2; stride / 2 * rows / 2],
        }
    }

    /// Fills a rectangle clipped to the picture.
    pub fn fill(&mut self, x: u32, y: u32, w: u32, h: u32, color: Yuv) {
        let x1 = (x.saturating_add(w)).min(self.width) as usize;
        let y1 = (y.saturating_add(h)).min(self.height) as usize;
        for row in (y as usize)..y1 {
            self.y[row * self.stride + x as usize..row * self.stride + x1].fill(color.0);
        }
        let c = self.stride / 2;
        for row in (y as usize / 2)..y1.div_ceil(2) {
            self.u[row * c + x as usize / 2..row * c + x1.div_ceil(2)].fill(color.1);
            self.v[row * c + x as usize / 2..row * c + x1.div_ceil(2)].fill(color.2);
        }
    }

    /// Draws decimal `value` with its top-left at (x, y), each glyph pixel `scale` wide.
    pub fn number(&mut self, x: u32, y: u32, scale: u32, value: u64, color: Yuv) {
        for (i, ch) in value.to_string().bytes().enumerate() {
            let glyph = DIGITS[usize::from(ch - b'0')];
            let left = x + i as u32 * 6 * scale;
            for (row, bits) in glyph.iter().enumerate() {
                for col in 0..5 {
                    if bits & (0x10 >> col) != 0 {
                        self.fill(
                            left + col * scale,
                            y + row as u32 * scale,
                            scale,
                            scale,
                            color,
                        );
                    }
                }
            }
        }
    }

    /// Luma and chroma of the pixel at (x, y).
    pub fn sample(&self, x: u32, y: u32) -> Yuv {
        let c = self.stride / 2;
        Yuv(
            self.y[y as usize * self.stride + x as usize],
            self.u[y as usize / 2 * c + x as usize / 2],
            self.v[y as usize / 2 * c + x as usize / 2],
        )
    }
}

struct BitWriter {
    bytes: Vec<u8>,
    bits: u32,
}

impl BitWriter {
    fn new() -> Self {
        Self {
            bytes: Vec::new(),
            bits: 0,
        }
    }
    fn u(&mut self, count: u32, value: u32) {
        for i in (0..count).rev() {
            if self.bits % 8 == 0 {
                self.bytes.push(0);
            }
            let bit = ((value >> i) & 1) as u8;
            let last = self.bytes.last_mut().expect("byte pushed");
            *last |= bit << (7 - self.bits % 8);
            self.bits += 1;
        }
    }
    fn ue(&mut self, value: u32) {
        let v = u64::from(value) + 1;
        let len = 64 - v.leading_zeros();
        self.u(len - 1, 0);
        for i in (0..len).rev() {
            self.u(1, ((v >> i) & 1) as u32);
        }
    }
    fn se(&mut self, value: i32) {
        let mapped = if value > 0 {
            (value as u32) * 2 - 1
        } else {
            (value.unsigned_abs()) * 2
        };
        self.ue(mapped);
    }
    fn align_zero(&mut self) {
        while self.bits % 8 != 0 {
            self.u(1, 0);
        }
    }
    fn bytes(&mut self, data: &[u8]) {
        debug_assert_eq!(self.bits % 8, 0);
        self.bytes.extend_from_slice(data);
        self.bits += 8 * data.len() as u32;
    }
    fn trailing(mut self) -> Vec<u8> {
        self.u(1, 1);
        self.align_zero();
        self.bytes
    }
}

/// Annex B NAL unit with emulation prevention.
fn nal(header: u8, rbsp: &[u8]) -> Vec<u8> {
    let mut out = vec![0, 0, 0, 1, header];
    let mut zeros = 0;
    for &byte in rbsp {
        if zeros >= 2 && byte <= 3 {
            out.push(3);
            zeros = 0;
        }
        out.push(byte);
        zeros = if byte == 0 { zeros + 1 } else { 0 };
    }
    out
}

/// I_PCM/P_Skip H.264 encoder; see the crate documentation.
pub struct Encoder {
    width: u32,
    height: u32,
    mbs_w: u32,
    mbs_h: u32,
    previous: Option<Canvas>,
    frame_num: u32,
    idr_id: u32,
}

impl Encoder {
    pub fn new(width: u32, height: u32) -> Self {
        Self {
            width,
            height,
            mbs_w: width.div_ceil(16),
            mbs_h: height.div_ceil(16),
            previous: None,
            frame_num: 0,
            idr_id: 0,
        }
    }

    /// SPS and PPS, Annex B: the codec configuration buffer.
    pub fn parameter_sets(&self) -> Vec<u8> {
        let mut sps = BitWriter::new();
        sps.u(8, 66); // profile_idc: baseline
        sps.u(8, 0xc0); // constraint_set0 and constraint_set1: constrained baseline
        sps.u(8, 31); // level_idc
        sps.ue(0); // seq_parameter_set_id
        sps.ue(0); // log2_max_frame_num_minus4
        sps.ue(2); // pic_order_cnt_type
        sps.ue(1); // max_num_ref_frames
        sps.u(1, 0); // gaps_in_frame_num_value_allowed_flag
        sps.ue(self.mbs_w - 1);
        sps.ue(self.mbs_h - 1);
        sps.u(1, 1); // frame_mbs_only_flag
        sps.u(1, 1); // direct_8x8_inference_flag
        let crop_right = (self.mbs_w * 16 - self.width) / 2;
        let crop_bottom = (self.mbs_h * 16 - self.height) / 2;
        if crop_right > 0 || crop_bottom > 0 {
            sps.u(1, 1);
            sps.ue(0);
            sps.ue(crop_right);
            sps.ue(0);
            sps.ue(crop_bottom);
        } else {
            sps.u(1, 0);
        }
        sps.u(1, 0); // vui_parameters_present_flag
        let mut pps = BitWriter::new();
        pps.ue(0); // pic_parameter_set_id
        pps.ue(0); // seq_parameter_set_id
        pps.u(1, 0); // entropy_coding_mode_flag: CAVLC
        pps.u(1, 0); // bottom_field_pic_order_in_frame_present_flag
        pps.ue(0); // num_slice_groups_minus1
        pps.ue(0); // num_ref_idx_l0_default_active_minus1
        pps.ue(0); // num_ref_idx_l1_default_active_minus1
        pps.u(1, 0); // weighted_pred_flag
        pps.u(2, 0); // weighted_bipred_idc
        pps.se(0); // pic_init_qp_minus26
        pps.se(0); // pic_init_qs_minus26
        pps.se(0); // chroma_qp_index_offset
        pps.u(1, 1); // deblocking_filter_control_present_flag
        pps.u(1, 0); // constrained_intra_pred_flag
        pps.u(1, 0); // redundant_pic_cnt_present_flag
        let mut out = nal(0x67, &sps.trailing());
        out.extend(nal(0x68, &pps.trailing()));
        out
    }

    /// Encodes one picture. The first picture, and any with `idr` set, is an IDR.
    /// Returns the Annex B access unit and whether it is an IDR.
    pub fn encode(&mut self, canvas: &Canvas, idr: bool) -> (Vec<u8>, bool) {
        assert_eq!((canvas.width, canvas.height), (self.width, self.height));
        let idr = idr || self.previous.is_none();
        let mut w = BitWriter::new();
        w.ue(0); // first_mb_in_slice
        w.ue(if idr { 7 } else { 5 }); // slice_type: all I or all P
        w.ue(0); // pic_parameter_set_id
        if idr {
            self.frame_num = 0;
        } else {
            self.frame_num = (self.frame_num + 1) % 16;
        }
        w.u(4, self.frame_num);
        if idr {
            w.ue(self.idr_id);
            self.idr_id = (self.idr_id + 1) % 65536;
            w.u(1, 0); // no_output_of_prior_pics_flag
            w.u(1, 0); // long_term_reference_flag
        } else {
            w.u(1, 0); // num_ref_idx_active_override_flag
            w.u(1, 0); // ref_pic_list_modification_flag_l0
            w.u(1, 0); // adaptive_ref_pic_marking_mode_flag
        }
        w.se(0); // slice_qp_delta
        w.ue(1); // disable_deblocking_filter_idc
        let total = self.mbs_w * self.mbs_h;
        let mut skipped = 0;
        for mb in 0..total {
            let (mx, my) = (mb % self.mbs_w, mb / self.mbs_w);
            let changed = idr
                || self
                    .previous
                    .as_ref()
                    .is_none_or(|p| !same_macroblock(p, canvas, mx, my));
            if !changed {
                skipped += 1;
                continue;
            }
            if !idr {
                w.ue(skipped);
                skipped = 0;
                w.ue(30); // mb_type I_PCM in a P slice
            } else {
                w.ue(25); // mb_type I_PCM
            }
            w.align_zero();
            w.bytes(&macroblock(canvas, mx, my));
        }
        if !idr && skipped > 0 {
            w.ue(skipped);
        }
        self.previous = Some(canvas.clone());
        let header = if idr { 0x65 } else { 0x41 };
        (nal(header, &w.trailing()), idr)
    }
}

fn macroblock(c: &Canvas, mx: u32, my: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity(384);
    let (x, y) = (mx as usize * 16, my as usize * 16);
    for row in y..y + 16 {
        out.extend(
            c.y[row * c.stride + x..row * c.stride + x + 16]
                .iter()
                .map(|&s| s.max(1)),
        );
    }
    let cs = c.stride / 2;
    for plane in [&c.u, &c.v] {
        for row in y / 2..y / 2 + 8 {
            out.extend(
                plane[row * cs + x / 2..row * cs + x / 2 + 8]
                    .iter()
                    .map(|&s| s.max(1)),
            );
        }
    }
    out
}

fn same_macroblock(a: &Canvas, b: &Canvas, mx: u32, my: u32) -> bool {
    let (x, y) = (mx as usize * 16, my as usize * 16);
    let luma = (y..y + 16).all(|row| {
        a.y[row * a.stride + x..row * a.stride + x + 16]
            == b.y[row * b.stride + x..row * b.stride + x + 16]
    });
    let cs = a.stride / 2;
    luma && [(&a.u, &b.u), (&a.v, &b.v)].iter().all(|(pa, pb)| {
        (y / 2..y / 2 + 8).all(|row| {
            pa[row * cs + x / 2..row * cs + x / 2 + 8] == pb[row * cs + x / 2..row * cs + x / 2 + 8]
        })
    })
}
