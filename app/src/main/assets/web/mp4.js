// H.264 Annex-B -> fragmented MP4 for Media Source Extensions.
// One video track, one sample per fragment, timescale 90 kHz.

export const TIMESCALE = 90000;

/** Splits an Annex-B buffer into NAL units (without start codes). */
export function splitNalUnits(data) {
  const units = [];
  const n = data.length;
  let i = 0;
  let start = -1;
  while (i + 2 < n) {
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      if (start >= 0) units.push(data.subarray(start, trimEnd(data, start, i)));
      i += 3;
      start = i;
    } else {
      i++;
    }
  }
  if (start >= 0 && start < n) units.push(data.subarray(start, n));
  return units;
}

// Drops the zero byte of a 4-byte start code that belongs to the next unit.
function trimEnd(data, start, end) {
  while (end > start && data[end - 1] === 0) end--;
  return end;
}

export const nalType = (unit) => unit[0] & 0x1f;

class BitReader {
  constructor(bytes) {
    // Remove emulation prevention bytes (00 00 03 -> 00 00).
    const out = [];
    for (let i = 0; i < bytes.length; i++) {
      if (i >= 2 && bytes[i] === 3 && bytes[i - 1] === 0 && bytes[i - 2] === 0) continue;
      out.push(bytes[i]);
    }
    this.bytes = out;
    this.pos = 0;
  }
  bit() {
    const b = (this.bytes[this.pos >> 3] >> (7 - (this.pos & 7))) & 1;
    this.pos++;
    return b;
  }
  bits(n) {
    let v = 0;
    for (let i = 0; i < n; i++) v = v * 2 + this.bit();
    return v;
  }
  ue() {
    let zeros = 0;
    while (this.bit() === 0 && zeros < 32) zeros++;
    return (2 ** zeros) - 1 + this.bits(zeros);
  }
  se() {
    const v = this.ue();
    return v & 1 ? (v + 1) / 2 : -v / 2;
  }
}

/** Reads profile, level and the display size from an SPS NAL unit. */
export function parseSps(sps) {
  const r = new BitReader(sps);
  r.bits(8); // NAL header
  const profile = r.bits(8);
  const compat = r.bits(8);
  const level = r.bits(8);
  r.ue(); // seq_parameter_set_id
  let chromaFormat = 1;
  if ([100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135].includes(profile)) {
    chromaFormat = r.ue();
    if (chromaFormat === 3) r.bit();
    r.ue(); // bit_depth_luma_minus8
    r.ue(); // bit_depth_chroma_minus8
    r.bit(); // qpprime_y_zero_transform_bypass_flag
    if (r.bit()) {
      const lists = chromaFormat === 3 ? 12 : 8;
      for (let i = 0; i < lists; i++) {
        if (!r.bit()) continue;
        const size = i < 6 ? 16 : 64;
        let last = 8;
        let next = 8;
        for (let j = 0; j < size; j++) {
          if (next !== 0) next = (last + r.se() + 256) % 256;
          last = next === 0 ? last : next;
        }
      }
    }
  }
  r.ue(); // log2_max_frame_num_minus4
  const pocType = r.ue();
  if (pocType === 0) {
    r.ue();
  } else if (pocType === 1) {
    r.bit();
    r.se();
    r.se();
    const cycle = r.ue();
    for (let i = 0; i < cycle; i++) r.se();
  }
  r.ue(); // max_num_ref_frames
  r.bit(); // gaps_in_frame_num_value_allowed_flag
  const widthMbs = r.ue() + 1;
  const heightMapUnits = r.ue() + 1;
  const frameMbsOnly = r.bit();
  if (!frameMbsOnly) r.bit();
  r.bit(); // direct_8x8_inference_flag
  let cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
  if (r.bit()) {
    cropLeft = r.ue();
    cropRight = r.ue();
    cropTop = r.ue();
    cropBottom = r.ue();
  }
  const subWidth = chromaFormat === 1 || chromaFormat === 2 ? 2 : 1;
  const subHeight = chromaFormat === 1 ? 2 : 1;
  const unitX = chromaFormat === 0 ? 1 : subWidth;
  const unitY = (chromaFormat === 0 ? 1 : subHeight) * (2 - frameMbsOnly);
  return {
    profile,
    compat,
    level,
    width: widthMbs * 16 - (cropLeft + cropRight) * unitX,
    height: (2 - frameMbsOnly) * heightMapUnits * 16 - (cropTop + cropBottom) * unitY,
    codec: 'avc1.' + [profile, compat, level].map((v) => v.toString(16).padStart(2, '0')).join(''),
  };
}

function u32(v) {
  return [(v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255];
}

function u16(v) {
  return [(v >>> 8) & 255, v & 255];
}

function concat(parts) {
  let size = 0;
  for (const p of parts) size += p.length;
  const out = new Uint8Array(size);
  let pos = 0;
  for (const p of parts) {
    out.set(p, pos);
    pos += p.length;
  }
  return out;
}

function box(type, ...payload) {
  const parts = payload.map((p) => (p instanceof Uint8Array ? p : new Uint8Array(p)));
  let size = 8;
  for (const p of parts) size += p.length;
  const head = new Uint8Array(8);
  head.set(u32(size), 0);
  for (let i = 0; i < 4; i++) head[4 + i] = type.charCodeAt(i);
  return concat([head, ...parts]);
}

const fullBox = (type, version, flags, ...payload) =>
  box(type, [version, (flags >> 16) & 255, (flags >> 8) & 255, flags & 255], ...payload);

// Unity transformation matrix, nine 32-bit fixed-point values.
const MATRIX = [0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000].flatMap(u32);

/** ftyp + moov for a single H.264 track. */
export function initSegment(sps, pps, info) {
  const { width, height } = info;
  const avcC = box('avcC', [1, sps[1], sps[2], sps[3], 0xff, 0xe1], u16(sps.length), sps, [1], u16(pps.length), pps);
  const avc1 = box(
    'avc1',
    [0, 0, 0, 0, 0, 0, 0, 1], // reserved, data_reference_index
    new Uint8Array(16), // pre_defined, reserved
    u16(width), u16(height),
    u32(0x00480000), u32(0x00480000), // 72 dpi
    u32(0), u16(1), // reserved, frame_count
    new Uint8Array(32), // compressorname
    u16(0x18), [0xff, 0xff], // depth, pre_defined
    avcC,
  );
  const empty = (type) => fullBox(type, 0, 0, u32(0));
  const stbl = box(
    'stbl',
    fullBox('stsd', 0, 0, u32(1), avc1),
    empty('stts'),
    empty('stsc'),
    fullBox('stsz', 0, 0, u32(0), u32(0)),
    empty('stco'),
  );
  const minf = box(
    'minf',
    fullBox('vmhd', 0, 1, new Uint8Array(8)),
    box('dinf', fullBox('dref', 0, 0, u32(1), fullBox('url ', 0, 1))),
    stbl,
  );
  const mdia = box(
    'mdia',
    fullBox('mdhd', 0, 0, u32(0), u32(0), u32(TIMESCALE), u32(0), [0x55, 0xc4, 0, 0]),
    fullBox('hdlr', 0, 0, u32(0), [0x76, 0x69, 0x64, 0x65], new Uint8Array(12), [0x56, 0x69, 0x64, 0x65, 0x6f, 0]),
    minf,
  );
  const tkhd = fullBox(
    'tkhd', 0, 3,
    u32(0), u32(0), u32(1), u32(0), u32(0), // times, track_ID, reserved, duration
    new Uint8Array(8), u16(0), u16(0), u16(0), u16(0), // reserved, layer, alternate_group, volume, reserved
    MATRIX,
    u32(width << 16), u32(height << 16),
  );
  const mvhd = fullBox(
    'mvhd', 0, 0,
    u32(0), u32(0), u32(1000), u32(0), // times, timescale, duration
    u32(0x00010000), u16(0x0100), new Uint8Array(10), // rate, volume, reserved
    MATRIX,
    new Uint8Array(24), u32(2), // pre_defined, next_track_ID
  );
  const mvex = box('mvex', fullBox('trex', 0, 0, u32(1), u32(1), u32(0), u32(0), u32(0)));
  const ftyp = box('ftyp', [0x69, 0x73, 0x6f, 0x6d], u32(0x200), [0x69, 0x73, 0x6f, 0x6d, 0x69, 0x73, 0x6f, 0x36, 0x61, 0x76, 0x63, 0x31, 0x6d, 0x70, 0x34, 0x31]);
  return concat([ftyp, box('moov', mvhd, box('trak', tkhd, mdia), mvex)]);
}

/** moof + mdat holding one sample made of the given NAL units. */
export function mediaSegment(sequence, decodeTime, duration, units, keyframe) {
  const sample = concat(units.flatMap((u) => [new Uint8Array(u32(u.length)), u]));
  const flags = keyframe ? 0x02000000 : 0x01010000;
  const hi = Math.floor(decodeTime / 2 ** 32);
  const lo = decodeTime >>> 0;
  const build = (dataOffset) =>
    box(
      'moof',
      fullBox('mfhd', 0, 0, u32(sequence)),
      box(
        'traf',
        fullBox('tfhd', 0, 0x020000, u32(1)),
        fullBox('tfdt', 1, 0, u32(hi), u32(lo)),
        fullBox('trun', 0, 0x000701, u32(1), u32(dataOffset), u32(duration), u32(sample.length), u32(flags)),
      ),
    );
  const moofSize = build(0).length;
  return concat([build(moofSize + 8), box('mdat', sample)]);
}
