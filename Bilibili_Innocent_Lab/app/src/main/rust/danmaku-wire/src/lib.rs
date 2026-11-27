//! `DmSegMobileReply` 线格式扫描/重写内核（弹幕净化快路径）。
//!
//! 职责边界：这里**只做字节编解码**——按安装期探针实测出的字段号提取信号、按保留位图重写字节；
//! 一切判定（权重阈值、彩字改色、语义过滤）都留在 Kotlin 侧的既有策略里，单一事实来源不拆成两份。
//!
//! 字节级安全约束（违反任何一条都返回错误码让调用方回退旧路径，绝不猜）：
//! - 只删整个 `elems`/`colorful_src` TLV 块、只把彩字 varint 的值字节替换为 0（tag 字节原样保留）；
//!   其余字节（含未知字段）逐字节原样拷贝，等价于 copy-on-change 保留无关字段。
//! - group（wire type 3/4）与非法 wire type（6/7）**只在本库自行解析的层级拒绝**；
//!   按长度跳过的载荷内部（嵌套未知子消息等）不解析、原样保留——这是有意的取舍。
//! - 读侧字段重复出现取最后一次、命名字段 wire type 不符按未知字段跳过，与 Java 解析器同语义。
//! - 六个字段号必须在 1..2^29-1 且同消息内互异（不同子消息如 elems=1 与 DmColorful.type=1 允许同号），
//!   否则整体拒绝（同消息碰撞会导致静默少提取，绝不带病工作）。
//!
//! 恐慌与内存边界：所有 C 入口 `catch_unwind` 后转错误码；`panic=unwind` 是硬合同（见 Cargo.toml），
//! panic=abort 会在宿主进程里直接 abort，PROTECTIVE 异常模式兜不住原生崩溃。
//! **catch_unwind 兜不住的只有三种**：分配失败 abort（见 [MAX_REPLY_BYTES] 与 try_reserve 的使用）、
//! 垫片传入越界指针的 UB（输入校验只能在 C 垫片边界做）、SIGSEGV 级错误。
//! 输入缓冲在 scan→结果消费期间不得被并发修改（JNI GetByteArrayElements 可能返回拷贝也可能钉住）。

use std::panic::{catch_unwind, AssertUnwindSafe};

/// C ABI 版本；垫片与 Kotlin 侧各自核对，不一致时不启用本库。
pub const ABI_VERSION: u32 = 1;

/// 防御性上限：超出即拒绝（回退旧路径）。真实弹幕分片是 KB 量级，16MiB 已是百倍冗余；
/// 同时它把一次性 `with_capacity` 的 OOM abort 风险钉在一个可接受的上界内。
pub const MAX_REPLY_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_ELEMS: usize = 65_536;
pub const MAX_SRCS: usize = 65_536;

/// 安装期探针在**真实宿主类**上实测出的字段号；不做任何硬编码猜测。
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WireFields {
    pub elems_field: u32,
    pub weight_field: u32,
    pub colorful_field: u32,
    pub content_field: u32,
    pub colorful_src_field: u32,
    pub colorful_type_field: u32,
}

impl WireFields {
    /// 六个号都在合法区间且同消息内字段号互异；同消息碰撞会导致静默少提取，整体拒绝。
    fn validated(&self) -> Result<(), WireError> {
        let all = [
            self.elems_field, self.weight_field, self.colorful_field,
            self.content_field, self.colorful_src_field, self.colorful_type_field,
        ];
        for value in all {
            if value == 0 || value > 536_870_911 {
                return Err(WireError::Fields);
            }
        }
        if self.elems_field == self.colorful_src_field {
            return Err(WireError::Fields);
        }
        if self.weight_field == self.colorful_field
            || self.weight_field == self.content_field
            || self.colorful_field == self.content_field
        {
            return Err(WireError::Fields);
        }
        Ok(())
    }
}

/// C ABI 错误码；0 之外的一切都表示"本次回退，不要用结果"。
pub const DW_OK: i32 = 0;
pub const DW_ERR_DECLINE: i32 = 1;
pub const DW_ERR_PANIC: i32 = 2;
pub const DW_ERR_ARGS: i32 = -1;

#[derive(Debug, PartialEq, Eq)]
pub enum WireError {
    Truncated,
    Group,
    InvalidWireType,
    TooManyElems,
    TooManySrcs,
    TooLarge,
    #[allow(dead_code)]
    NonCanonical,
    MaskLength,
    Fields,
}

/// 单条弹幕信号：weight/colorful 缺席为 None（对应 Java 侧"字段未设"），content 为
/// 相对输入缓冲的绝对偏移（不拷贝字符串；Kotlin 侧自行按区间切 UTF-8）。
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ElemSignal {
    pub weight: Option<i32>,
    pub colorful: Option<i32>,
    pub content_start: usize,
    pub content_len: usize,
    pub has_content: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SrcSignal {
    pub kind: Option<i32>,
}

#[derive(Debug, PartialEq, Eq)]
pub struct ScanOut {
    pub elems: Vec<ElemSignal>,
    pub srcs: Vec<SrcSignal>,
}

pub struct RewriteMask<'a> {
    pub keep_elem: &'a [u8],
    pub recolor_elem: &'a [u8],
    pub keep_src: &'a [u8],
}

struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Cursor { buf, pos: 0 }
    }

    fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    fn take(&mut self, len: usize) -> Option<&'a [u8]> {
        if self.remaining() < len {
            return None;
        }
        let start = self.pos;
        self.pos += len;
        Some(&self.buf[start..start + len])
    }

    /// 标准 varint：最多 10 字节；超出 64 位的溢出位丢弃（protobuf 读侧同语义），
    /// 第 10 字节仍带续位或输入截断都返回 None。
    fn varint(&mut self) -> Option<u64> {
        let mut value: u64 = 0;
        let mut shift = 0u32;
        for _ in 0..10 {
            let byte = self.take(1)?[0] as u64;
            if shift < 64 {
                value |= (byte & 0x7f) << shift;
            }
            if byte & 0x80 == 0 {
                return Some(value);
            }
            shift += 7;
        }
        None
    }

    /// 读一个 tag，返回 (字段号, wire type)；字段号 0 非法。
    fn tag(&mut self) -> Option<(u32, u8)> {
        let raw = self.varint()?;
        let field = (raw >> 3) as u32;
        let wt = (raw & 7) as u8;
        if field == 0 || raw >> 32 != 0 {
            return None;
        }
        Some((field, wt))
    }

    /// 读 wire type 2 的长度并直接截取负载。长度先与剩余字节比较再转 usize：
    /// 32 位平台上 `u64 as usize` 会回绕，必须先比后转，否则损坏输入会被静默误读。
    fn take_delimited(&mut self) -> Option<&'a [u8]> {
        let len = self.varint()?;
        if len > self.remaining() as u64 {
            return None;
        }
        self.take(len as usize)
    }

    /// 按 wire type 跳过负载。group（3/4）与非法 wire type（6/7）在本库解析的层级拒绝；
    /// 按长度跳过的载荷内部不在此列（不解析、原样保留）。
    fn skip_payload(&mut self, wt: u8) -> Result<(), WireError> {
        match wt {
            0 => {
                self.varint().ok_or(WireError::Truncated)?;
            }
            1 => {
                self.take(8).ok_or(WireError::Truncated)?;
            }
            2 => {
                self.take_delimited().ok_or(WireError::Truncated)?;
            }
            5 => {
                self.take(4).ok_or(WireError::Truncated)?;
            }
            3 | 4 => return Err(WireError::Group),
            _ => return Err(WireError::InvalidWireType),
        }
        Ok(())
    }
}

/// protobuf-java readInt32 的语义：varint 按 int64 读出后截断到低 32 位。
fn varint_to_i32(value: u64) -> i32 {
    value as u32 as i32
}

/// `base` 是顶层扫描缓冲的首地址；content 偏移按它计算，Kotlin 侧直接用区间切原数组。
fn scan_elem(buf: &[u8], fields: &WireFields, base: usize) -> Result<ElemSignal, WireError> {
    let mut cur = Cursor::new(buf);
    let mut signal = ElemSignal::default();
    while cur.remaining() > 0 {
        let (field, wt) = cur.tag().ok_or(WireError::Truncated)?;
        if field == fields.weight_field && wt == 0 {
            let value = cur.varint().ok_or(WireError::Truncated)?;
            signal.weight = Some(varint_to_i32(value));
        } else if field == fields.colorful_field && wt == 0 {
            let value = cur.varint().ok_or(WireError::Truncated)?;
            signal.colorful = Some(varint_to_i32(value));
        } else if field == fields.content_field && wt == 2 {
            let payload = cur.take_delimited().ok_or(WireError::Truncated)?;
            signal.has_content = true;
            signal.content_start = (payload.as_ptr() as usize)
                .checked_sub(base)
                .ok_or(WireError::Truncated)?;
            signal.content_len = payload.len();
        } else {
            cur.skip_payload(wt)?;
        }
    }
    Ok(signal)
}

fn scan_src(buf: &[u8], fields: &WireFields) -> Result<SrcSignal, WireError> {
    let mut cur = Cursor::new(buf);
    let mut signal = SrcSignal { kind: None };
    while cur.remaining() > 0 {
        let (field, wt) = cur.tag().ok_or(WireError::Truncated)?;
        if field == fields.colorful_type_field && wt == 0 {
            let value = cur.varint().ok_or(WireError::Truncated)?;
            signal.kind = Some(varint_to_i32(value));
        } else {
            cur.skip_payload(wt)?;
        }
    }
    Ok(signal)
}

/// 扫描顶层：只认 `elems`/`colorful_src` 两个字段号（其余字段——含未知字段——只跳过）。
/// 两个命名字段自身 wire type 不是 2 时按未知字段处理，与 Java 解析器一致。
pub fn scan(buf: &[u8], fields: &WireFields) -> Result<ScanOut, WireError> {
    fields.validated()?;
    if buf.len() > MAX_REPLY_BYTES {
        return Err(WireError::TooLarge);
    }
    let mut cur = Cursor::new(buf);
    let mut elems = Vec::new();
    let mut srcs = Vec::new();
    while cur.remaining() > 0 {
        let (field, wt) = cur.tag().ok_or(WireError::Truncated)?;
        let is_elem = field == fields.elems_field && wt == 2;
        let is_src = field == fields.colorful_src_field && wt == 2;
        if is_elem || is_src {
            let payload = cur.take_delimited().ok_or(WireError::Truncated)?;
            if is_elem {
                if elems.len() >= MAX_ELEMS {
                    return Err(WireError::TooManyElems);
                }
                elems.try_reserve(1).map_err(|_| WireError::TooLarge)?;
                elems.push(scan_elem(payload, fields, buf.as_ptr() as usize)?);
            } else {
                if srcs.len() >= MAX_SRCS {
                    return Err(WireError::TooManySrcs);
                }
                srcs.try_reserve(1).map_err(|_| WireError::TooLarge)?;
                srcs.push(scan_src(payload, fields)?);
            }
        } else {
            cur.skip_payload(wt)?;
        }
    }
    Ok(ScanOut { elems, srcs })
}

fn put_varint(out: &mut Vec<u8>, mut value: u64) -> Result<(), WireError> {
    loop {
        let mut byte = (value & 0x7f) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        if out.try_reserve(1).is_err() {
            return Err(WireError::TooLarge);
        }
        out.push(byte);
        if value == 0 {
            return Ok(());
        }
    }
}

fn put_tag(out: &mut Vec<u8>, field: u32, wt: u8) -> Result<(), WireError> {
    put_varint(out, ((field as u64) << 3) | wt as u64)
}

/// 重写一条弹幕：把所有 `wt=0` 彩字字段的**值字节**换成 varint(0)（tag 字节原样保留），
/// 确保按 Protobuf last-wins 及任何读取口径解码都为 0；其余字段逐字节原样复制。
fn rewrite_elem(body: &[u8], fields: &WireFields, out: &mut Vec<u8>) -> Result<(), WireError> {
    let mut cur = Cursor::new(body);
    while cur.remaining() > 0 {
        let start = cur.pos;
        let (field, wt) = cur.tag().ok_or(WireError::Truncated)?;
        // tag 可能是 1..5 字节的 varint；记录读完 tag 的位置，改色时原样保留 tag 字节。
        let tag_end = cur.pos;
        if field == fields.colorful_field && wt == 0 {
            let _ = cur.varint().ok_or(WireError::Truncated)?;
            // 原始 tag 字节 + 规范编码的 varint(0)；值长度变化由外层的新长度前缀吸收。
            out.try_reserve(cur.pos - start + 1).map_err(|_| WireError::TooLarge)?;
            out.extend_from_slice(&body[start..tag_end]);
            out.push(0);
        } else {
            cur.skip_payload(wt)?;
            out.try_reserve(cur.pos - start).map_err(|_| WireError::TooLarge)?;
            out.extend_from_slice(&body[start..cur.pos]);
        }
    }
    Ok(())
}

/// 按位图重写整条 reply 字节。`keep/recolor` 的长度必须与扫描计数一致（调用方保证），
/// 顺序即字节序。无任何删除/改色时输出与输入逐字节相同（全部走原样拷贝路径）。
pub fn rewrite(buf: &[u8], fields: &WireFields, mask: &RewriteMask) -> Result<Vec<u8>, WireError> {
    fields.validated()?;
    if buf.len() > MAX_REPLY_BYTES {
        return Err(WireError::TooLarge);
    }
    if mask.keep_elem.len() != mask.recolor_elem.len() {
        return Err(WireError::MaskLength);
    }
    if mask.keep_elem.len() > MAX_ELEMS {
        return Err(WireError::TooManyElems);
    }
    if mask.keep_src.len() > MAX_SRCS {
        return Err(WireError::TooManySrcs);
    }
    let mut out = Vec::new();
    out.try_reserve(buf.len()).map_err(|_| WireError::TooLarge)?;
    let mut cur = Cursor::new(buf);
    let mut elem_index = 0usize;
    let mut src_index = 0usize;
    // 改写 elem 时复用的小缓冲，避免每条弹幕一次分配。
    let mut elem_body = Vec::new();
    while cur.remaining() > 0 {
        let start = cur.pos;
        let (field, wt) = cur.tag().ok_or(WireError::Truncated)?;
        let is_elem = field == fields.elems_field && wt == 2;
        let is_src = field == fields.colorful_src_field && wt == 2;
        if is_elem || is_src {
            let payload = cur.take_delimited().ok_or(WireError::Truncated)?;
            if is_elem {
                if elem_index >= mask.keep_elem.len() || elem_index >= mask.recolor_elem.len() {
                    return Err(WireError::MaskLength);
                }
                let keep = mask.keep_elem[elem_index] != 0;
                let recolor = mask.recolor_elem[elem_index] != 0;
                elem_index += 1;
                if !keep {
                    continue;
                }
                if recolor {
                    elem_body.clear();
                    rewrite_elem(payload, fields, &mut elem_body)?;
                    put_tag(&mut out, field, 2)?;
                    put_varint(&mut out, elem_body.len() as u64)?;
                    out.try_reserve(elem_body.len()).map_err(|_| WireError::TooLarge)?;
                    out.extend_from_slice(&elem_body);
                } else {
                    let span = cur.pos - start;
                    out.try_reserve(span).map_err(|_| WireError::TooLarge)?;
                    out.extend_from_slice(&buf[start..cur.pos]);
                }
            } else {
                if src_index >= mask.keep_src.len() {
                    return Err(WireError::MaskLength);
                }
                let keep = mask.keep_src[src_index] != 0;
                src_index += 1;
                if keep {
                    let span = cur.pos - start;
                    out.try_reserve(span).map_err(|_| WireError::TooLarge)?;
                    out.extend_from_slice(&buf[start..cur.pos]);
                }
            }
        } else {
            cur.skip_payload(wt)?;
            let span = cur.pos - start;
            out.try_reserve(span).map_err(|_| WireError::TooLarge)?;
            out.extend_from_slice(&buf[start..cur.pos]);
        }
    }
    if elem_index != mask.keep_elem.len() || elem_index != mask.recolor_elem.len()
        || src_index != mask.keep_src.len()
    {
        return Err(WireError::MaskLength);
    }
    Ok(out)
}

/// 扫描结果压平成 i32 数组（Kotlin 侧零解析成本读取）：
/// `[elem_count, src_count, (weight, colorful, flags, content_start, content_len)×elem, (type, flags)×src]`
/// flags：bit0=weight 存在、bit1=colorful 存在、bit2=content 存在（src 记录 bit0=type 存在）。
/// 用存在位而不是哨兵值：任何 32 位值都可能是合法字段值，哨兵会与之碰撞。
pub fn flatten(scan: &ScanOut) -> Result<Vec<i32>, WireError> {
    let total = 2 + scan.elems.len() * 5 + scan.srcs.len() * 2;
    let mut out = Vec::new();
    out.try_reserve(total).map_err(|_| WireError::TooLarge)?;
    out.push(scan.elems.len() as i32);
    out.push(scan.srcs.len() as i32);
    for elem in &scan.elems {
        let mut flags = 0i32;
        if elem.weight.is_some() {
            flags |= 1;
        }
        if elem.colorful.is_some() {
            flags |= 2;
        }
        if elem.has_content {
            flags |= 4;
        }
        out.push(elem.weight.unwrap_or(0));
        out.push(elem.colorful.unwrap_or(0));
        out.push(flags);
        out.push(i32::try_from(elem.content_start).map_err(|_| WireError::TooLarge)?);
        out.push(i32::try_from(elem.content_len).map_err(|_| WireError::TooLarge)?);
    }
    for src in &scan.srcs {
        let mut flags = 0i32;
        if src.kind.is_some() {
            flags |= 1;
        }
        out.push(src.kind.unwrap_or(0));
        out.push(flags);
    }
    Ok(out)
}

// ---------------- C ABI ----------------

/// 把 Vec 的堆缓冲以 (ptr, len, cap) 零重分配移交给 C 侧，避免 shrink realloc 与 OOM abort；
/// 空缓冲返回 (null, 0, 0)，避免把非分配的悬垂指针交出去。
fn handover_vec<T>(vec: Vec<T>) -> (*mut T, usize, usize) {
    if vec.is_empty() {
        return (std::ptr::null_mut(), 0, 0);
    }
    let mut vec = std::mem::ManuallyDrop::new(vec);
    (vec.as_mut_ptr(), vec.len(), vec.capacity())
}

fn handover_i32(vec: Vec<i32>) -> (*mut i32, usize, usize) {
    handover_vec(vec)
}

fn handover_u8(vec: Vec<u8>) -> (*mut u8, usize, usize) {
    handover_vec(vec)
}

/// 回收 C 侧不再使用的 i32 扫描缓冲。len/cap 必须与移交时一致；cap==0 时为无操作。
/// # Safety
/// ptr 非空时必须来自 [handover_i32] 的移交且未被释放过。
#[no_mangle]
pub unsafe extern "C" fn dw_free_i32(ptr: *mut i32, len: usize, cap: usize) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if ptr.is_null() || cap == 0 || len > cap {
            return;
        }
        drop(Vec::from_raw_parts(ptr, len, cap));
    }));
}

/// 回收 C 侧不再使用的字节缓冲。len/cap 必须与移交时一致；cap==0 时为无操作。
/// # Safety
/// ptr 非空时必须来自 [handover_u8] 的移交且未被释放过。
#[no_mangle]
pub unsafe extern "C" fn dw_free_u8(ptr: *mut u8, len: usize, cap: usize) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if ptr.is_null() || cap == 0 || len > cap {
            return;
        }
        drop(Vec::from_raw_parts(ptr, len, cap));
    }));
}

#[repr(C)]
#[derive(Clone, Copy)]
struct DwScanArgs {
    buf: *const u8,
    buf_len: usize,
    fields: *const WireFields,
    out_ptr: *mut *mut i32,
    out_len: *mut usize,
    out_cap: *mut usize,
}

unsafe fn scan_impl(args: DwScanArgs) -> i32 {
    if args.buf.is_null()
        || args.fields.is_null()
        || args.out_ptr.is_null()
        || args.out_len.is_null()
        || args.out_cap.is_null()
    {
        return DW_ERR_ARGS;
    }
    let buf = std::slice::from_raw_parts(args.buf, args.buf_len);
    let fields = *args.fields;
    match scan(buf, &fields).and_then(|result| flatten(&result)) {
        Ok(flat) => {
            let (ptr, len, cap) = handover_i32(flat);
            *args.out_ptr = ptr;
            *args.out_len = len;
            *args.out_cap = cap;
            DW_OK
        }
        Err(_) => DW_ERR_DECLINE,
    }
}

/// # Safety
/// 参数必须由 C 垫片按约定构造；输出指针由垫片在调用后立即拷贝并 dw_free_i32。
#[no_mangle]
pub unsafe extern "C" fn dw_scan(
    buf: *const u8,
    buf_len: usize,
    fields: *const WireFields,
    out_ptr: *mut *mut i32,
    out_len: *mut usize,
    out_cap: *mut usize,
) -> i32 {
    let args = DwScanArgs { buf, buf_len, fields, out_ptr, out_len, out_cap };
    match catch_unwind(AssertUnwindSafe(|| scan_impl(args))) {
        Ok(code) => code,
        Err(_) => DW_ERR_PANIC,
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct DwRewriteArgs {
    buf: *const u8,
    buf_len: usize,
    fields: *const WireFields,
    keep_elem: *const u8,
    keep_elem_len: usize,
    recolor_elem: *const u8,
    recolor_elem_len: usize,
    keep_src: *const u8,
    keep_src_len: usize,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
    out_cap: *mut usize,
}

unsafe fn rewrite_impl(args: DwRewriteArgs) -> i32 {
    if args.buf.is_null() || args.fields.is_null() || args.keep_elem.is_null()
        || args.recolor_elem.is_null() || args.keep_src.is_null()
        || args.out_ptr.is_null() || args.out_len.is_null() || args.out_cap.is_null()
    {
        return DW_ERR_ARGS;
    }
    let buf = std::slice::from_raw_parts(args.buf, args.buf_len);
    let fields = *args.fields;
    let mask = RewriteMask {
        keep_elem: std::slice::from_raw_parts(args.keep_elem, args.keep_elem_len),
        recolor_elem: std::slice::from_raw_parts(args.recolor_elem, args.recolor_elem_len),
        keep_src: std::slice::from_raw_parts(args.keep_src, args.keep_src_len),
    };
    match rewrite(buf, &fields, &mask) {
        Ok(result) => {
            let (ptr, len, cap) = handover_u8(result);
            *args.out_ptr = ptr;
            *args.out_len = len;
            *args.out_cap = cap;
            DW_OK
        }
        Err(_) => DW_ERR_DECLINE,
    }
}

/// # Safety
/// 参数必须由 C 垫片按约定构造；输出指针由垫片在调用后立即拷贝并 dw_free_u8。
#[no_mangle]
pub unsafe extern "C" fn dw_rewrite(
    buf: *const u8,
    buf_len: usize,
    fields: *const WireFields,
    keep_elem: *const u8,
    keep_elem_len: usize,
    recolor_elem: *const u8,
    recolor_elem_len: usize,
    keep_src: *const u8,
    keep_src_len: usize,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
    out_cap: *mut usize,
) -> i32 {
    let args = DwRewriteArgs {
        buf, buf_len, fields, keep_elem, keep_elem_len, recolor_elem, recolor_elem_len,
        keep_src, keep_src_len, out_ptr, out_len, out_cap,
    };
    match catch_unwind(AssertUnwindSafe(|| rewrite_impl(args))) {
        Ok(code) => code,
        Err(_) => DW_ERR_PANIC,
    }
}

#[no_mangle]
pub extern "C" fn dw_abi_version() -> u32 {
    ABI_VERSION
}

// ---------------- 测试 ----------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;

    const F: WireFields = WireFields {
        elems_field: 1,
        weight_field: 8,
        colorful_field: 11,
        content_field: 10,
        colorful_src_field: 4,
        colorful_type_field: 2,
    };

    fn tag(field: u32, wt: u8) -> Vec<u8> {
        let mut out = Vec::new();
        put_tag(&mut out, field, wt).unwrap();
        out
    }

    fn varint_field(field: u32, value: u64) -> Vec<u8> {
        let mut out = tag(field, 0);
        put_varint(&mut out, value).unwrap();
        out
    }

    fn bytes_field(field: u32, payload: &[u8]) -> Vec<u8> {
        let mut out = tag(field, 2);
        put_varint(&mut out, payload.len() as u64).unwrap();
        out.extend_from_slice(payload);
        out
    }

    fn elem(weight: Option<u64>, colorful: Option<u64>, content: Option<&[u8]>) -> Vec<u8> {
        let mut body = Vec::new();
        if let Some(w) = weight {
            body.extend(varint_field(F.weight_field, w));
        }
        if let Some(c) = colorful {
            body.extend(varint_field(F.colorful_field, c));
        }
        if let Some(text) = content {
            body.extend(bytes_field(F.content_field, text));
        }
        bytes_field(F.elems_field, &body)
    }

    fn src(kind: Option<u64>) -> Vec<u8> {
        let mut body = Vec::new();
        if let Some(k) = kind {
            body.extend(varint_field(F.colorful_type_field, k));
        }
        bytes_field(F.colorful_src_field, &body)
    }

    #[test]
    fn scan_reads_mixed_elements_and_sources() {
        let mut reply = Vec::new();
        reply.extend(bytes_field(99, b"unknown top level")); // 未知字段
        reply.extend(elem(Some(7), None, Some("甲".as_bytes())));
        reply.extend(elem(None, Some(60001), None));
        reply.extend(src(Some(60001)));
        reply.extend(varint_field(7, 12345)); // 其他顶层字段
        reply.extend(src(None));
        let out = scan(&reply, &F).unwrap();
        assert_eq!(out.elems.len(), 2);
        assert_eq!(out.elems[0].weight, Some(7));
        assert_eq!(out.elems[0].colorful, None);
        assert!(out.elems[0].has_content);
        assert_eq!(out.elems[1].weight, None);
        assert_eq!(out.elems[1].colorful, Some(60001));
        assert!(!out.elems[1].has_content);
        assert_eq!(out.srcs.len(), 2);
        assert_eq!(out.srcs[0].kind, Some(60001));
        assert_eq!(out.srcs[1].kind, None);
        // content 区间指向原文。
        let start = out.elems[0].content_start;
        let len = out.elems[0].content_len;
        assert_eq!(&reply[start..start + len], "甲".as_bytes());
    }

    #[test]
    fn scan_takes_last_occurrence_like_java_parser() {
        // 重复的 weight、重复的 content 都取最后一次。
        let mut body = Vec::new();
        body.extend(varint_field(F.weight_field, 1));
        body.extend(varint_field(F.weight_field, 9));
        body.extend(bytes_field(F.content_field, b"a"));
        body.extend(bytes_field(F.content_field, b"bc"));
        let reply = bytes_field(F.elems_field, &body);
        let out = scan(&reply, &F).unwrap();
        assert_eq!(out.elems[0].weight, Some(9));
        assert_eq!(&reply[out.elems[0].content_start..out.elems[0].content_start + 2], b"bc");
    }

    #[test]
    fn scan_rejects_truncated_groups_and_bad_wire_types() {
        // 截断的长度前缀。
        let mut reply = tag(F.elems_field, 2);
        reply.extend_from_slice(&[0x7f]);
        assert!(scan(&reply, &F).is_err());
        // group wire type（解析层级）。
        let mut reply = Vec::new();
        reply.extend(tag(5, 3));
        assert!(matches!(scan(&reply, &F), Err(WireError::Group)));
        // 非法 wire type 6。
        let mut reply = Vec::new();
        reply.extend(tag(5, 6));
        assert!(matches!(scan(&reply, &F), Err(WireError::InvalidWireType)));
    }

    #[test]
    fn skipped_payload_containing_groups_is_preserved_verbatim() {
        // 载荷内部的 group 不解析、原样保留（解析层级只在顶层与 elem/src 内部）。
        let mut inner = Vec::new();
        inner.extend(tag(9, 3)); // 未知子消息里塞 group 片段
        inner.extend_from_slice(&[0x01, 0x02]);
        let mut reply = bytes_field(99, &inner);
        reply.extend(elem(Some(7), None, None));
        let out = scan(&reply, &F).unwrap();
        assert_eq!(out.elems.len(), 1);
        assert!(reply.windows(2).any(|w| w == &inner[..2]));
        let keep = [1u8];
        let no_recolor = [0u8];
        let empty_src = [0u8; 0];
        let same = rewrite(&reply, &F, &RewriteMask { keep_elem: &keep, recolor_elem: &no_recolor, keep_src: &empty_src }).unwrap();
        assert_eq!(same, reply);
    }

    #[test]
    fn colliding_or_out_of_range_fields_are_rejected() {
        let mut same_reply = F;
        same_reply.colorful_src_field = same_reply.elems_field;
        assert!(matches!(scan(&[], &same_reply), Err(WireError::Fields)));
        assert!(matches!(rewrite(&[], &same_reply, &RewriteMask { keep_elem: &[], recolor_elem: &[], keep_src: &[] }), Err(WireError::Fields)));
        let mut bad = F;
        bad.weight_field = bad.colorful_field;
        assert!(matches!(scan(&[], &bad), Err(WireError::Fields)));
        assert!(matches!(rewrite(&[], &bad, &RewriteMask { keep_elem: &[], recolor_elem: &[], keep_src: &[] }), Err(WireError::Fields)));
        let mut bad2 = F;
        bad2.weight_field = bad2.content_field;
        assert!(matches!(scan(&[], &bad2), Err(WireError::Fields)));
        let mut bad3 = F;
        bad3.colorful_field = bad3.content_field;
        assert!(matches!(scan(&[], &bad3), Err(WireError::Fields)));
        let mut zero = F;
        zero.elems_field = 0;
        assert!(matches!(scan(&[], &zero), Err(WireError::Fields)));
        let mut huge = F;
        huge.elems_field = 536_870_912;
        assert!(matches!(scan(&[], &huge), Err(WireError::Fields)));
    }

    #[test]
    fn real_bilibili_cross_message_field_numbers_are_allowed() {
        // Real protocol: DmSegMobileReply.elems = 1 AND DmColorful.type = 1 (plus cross-message overlap with weight).
        let f = WireFields {
            elems_field: 1,
            weight_field: 8,
            colorful_field: 11,
            content_field: 10,
            colorful_src_field: 4,
            colorful_type_field: 1,
        };
        let mut elem1_body = Vec::new();
        elem1_body.extend(varint_field(f.weight_field, 2));
        elem1_body.extend(varint_field(f.colorful_field, 60001));
        elem1_body.extend(bytes_field(f.content_field, b"drop"));
        let mut elem2_body = Vec::new();
        elem2_body.extend(varint_field(f.weight_field, 9));
        elem2_body.extend(varint_field(f.colorful_field, 60001));
        elem2_body.extend(bytes_field(f.content_field, b"keep"));
        let mut src_body = Vec::new();
        src_body.extend(varint_field(f.colorful_type_field, 60001));

        let mut reply = Vec::new();
        reply.extend(bytes_field(f.elems_field, &elem1_body));
        reply.extend(bytes_field(f.elems_field, &elem2_body));
        reply.extend(bytes_field(f.colorful_src_field, &src_body));

        let out = scan(&reply, &f).unwrap();
        assert_eq!(out.elems.len(), 2);
        assert_eq!(out.elems[0].weight, Some(2));
        assert_eq!(out.elems[1].weight, Some(9));
        assert_eq!(out.srcs.len(), 1);
        assert_eq!(out.srcs[0].kind, Some(60001));

        let rewritten = rewrite(
            &reply,
            &f,
            &RewriteMask { keep_elem: &[0, 1], recolor_elem: &[0, 1], keep_src: &[0] },
        )
        .unwrap();
        let rescan = scan(&rewritten, &f).unwrap();
        assert_eq!(rescan.elems.len(), 1);
        assert_eq!(rescan.elems[0].colorful, Some(0));
        assert_eq!(rescan.srcs.len(), 0);
    }

    #[test]
    fn signed_high_bit_length_and_truncated_inner_varints_are_rejected() {
        // 0x8000_0000_0000_0005 as 10-byte varint: signed i64 negative (-9223372036854775803) with low32=5.
        let mut high_bit_len = tag(F.elems_field, 2);
        high_bit_len.extend_from_slice(&[0x85, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01]);
        high_bit_len.extend_from_slice(b"hello");
        assert!(matches!(scan(&high_bit_len, &F), Err(WireError::Truncated)));
        assert!(matches!(
            rewrite(&high_bit_len, &F, &RewriteMask { keep_elem: &[], recolor_elem: &[], keep_src: &[] }),
            Err(WireError::Truncated)
        ));

        // Truncated varint for weight inside elem.
        let mut trunc_weight = tag(F.weight_field, 0);
        trunc_weight.push(0x80);
        let reply_w = bytes_field(F.elems_field, &trunc_weight);
        assert!(matches!(scan(&reply_w, &F), Err(WireError::Truncated)));

        // Truncated varint for colorful_type inside src.
        let mut trunc_type = tag(F.colorful_type_field, 0);
        trunc_type.push(0x80);
        let reply_s = bytes_field(F.colorful_src_field, &trunc_type);
        assert!(matches!(scan(&reply_s, &F), Err(WireError::Truncated)));
    }

    #[test]
    fn rewrite_drops_recolors_and_preserves_unknown_bytes() {
        let mut reply = Vec::new();
        reply.extend(bytes_field(99, b"\xde\xad\xbe\xef"));
        reply.extend(elem(Some(7), None, Some("keep-me".as_bytes()))); // 0 保留
        reply.extend(elem(Some(1), Some(60001), Some("drop".as_bytes()))); // 1 删除
        reply.extend(elem(Some(5), Some(60001), None)); // 2 保留 + 改色
        reply.extend(src(Some(60001))); // 0 删除
        reply.extend(src(Some(0))); // 1 保留
        reply.extend(varint_field(7, 77));

        let scan_out = scan(&reply, &F).unwrap();
        let keep_elem = [1u8, 0, 1];
        let recolor = [0u8, 0, 1];
        let keep_src = [0u8, 1];
        let rewritten = rewrite(
            &reply,
            &F,
            &RewriteMask { keep_elem: &keep_elem, recolor_elem: &recolor, keep_src: &keep_src },
        )
        .unwrap();

        // 语义复检：只剩 2 条 elem、1 条 src；改色条目的彩字值为 0。
        let rescan = scan(&rewritten, &F).unwrap();
        assert_eq!(rescan.elems.len(), 2);
        assert_eq!(rescan.srcs.len(), 1);
        assert_eq!(rescan.elems[0].weight, Some(7));
        assert_eq!(rescan.elems[1].weight, Some(5));
        assert_eq!(rescan.elems[1].colorful, Some(0));
        assert_eq!(rescan.srcs[0].kind, Some(0));
        // 未知字段与其他顶层字段逐字节保留。
        assert!(rewritten.windows(4).any(|w| w == b"\xde\xad\xbe\xef"));
        let expect_tail = varint_field(7, 77);
        assert_eq!(&rewritten[rewritten.len() - expect_tail.len()..], &expect_tail[..]);

        // 无改动位图 = 逐字节原样输出。
        let all_keep = vec![1u8; scan_out.elems.len()];
        let no_recolor = vec![0u8; scan_out.elems.len()];
        let all_src = vec![1u8; scan_out.srcs.len()];
        let same = rewrite(
            &reply,
            &F,
            &RewriteMask { keep_elem: &all_keep, recolor_elem: &no_recolor, keep_src: &all_src },
        )
        .unwrap();
        assert_eq!(same, reply);
    }

    #[test]
    fn rewrite_preserves_original_tag_bytes_when_recoloring() {
        // 字段号 11 的 tag 是单字节；该用例钉死"只换值字节、不规范化 tag"。
        let reply = elem(Some(5), Some(60001), None);
        let keep = [1u8];
        let recolor = [1u8];
        let empty_src = [0u8; 0];
        let rewritten = rewrite(&reply, &F, &RewriteMask { keep_elem: &keep, recolor_elem: &recolor, keep_src: &empty_src }).unwrap();
        // 手工拼期望：tag(colorful,0)=0x58 + 0x00。
        assert!(rewritten.windows(2).any(|w| w == &[0x58, 0x00][..]));
        let rescan = scan(&rewritten, &F).unwrap();
        assert_eq!(rescan.elems[0].colorful, Some(0));
    }

    #[test]
    fn rewrite_rejects_mask_mismatch() {
        let reply = elem(Some(5), Some(60001), None);
        let keep = [1u8];
        let src = [0u8; 0];
        // keep 位图过短。
        let no_recolor = [0u8];
        let keep_short = [1u8, 1];
        assert!(matches!(
            rewrite(&reply, &F, &RewriteMask { keep_elem: &keep_short, recolor_elem: &no_recolor, keep_src: &src }),
            Err(WireError::MaskLength)
        ));
        // recolor 位图过长也要拒绝（终检同时核对两个位图长度）。
        let recolor_long = [0u8, 0, 0];
        assert!(matches!(
            rewrite(&reply, &F, &RewriteMask { keep_elem: &keep, recolor_elem: &recolor_long, keep_src: &src }),
            Err(WireError::MaskLength)
        ));
    }

    #[test]
    fn rewrite_clears_duplicate_colorful_and_preserves_unknown_fields() {
        let mut body = Vec::new();
        body.extend(varint_field(F.weight_field, 7));
        body.extend(varint_field(F.colorful_field, 123));
        body.extend(bytes_field(99, b"elem-unknown-mid"));
        body.extend(varint_field(F.colorful_field, 60001));
        body.extend(bytes_field(F.content_field, b"dup-color"));

        let mut reply = Vec::new();
        reply.extend(bytes_field(88, b"\xca\xfe\xba\xbe"));
        reply.extend(bytes_field(F.elems_field, &body));
        reply.extend(varint_field(7, 999));

        let initial = scan(&reply, &F).unwrap();
        assert_eq!(initial.elems[0].colorful, Some(60001));

        let keep = [1u8];
        let recolor = [1u8];
        let src = [0u8; 0];
        let rewritten = rewrite(
            &reply,
            &F,
            &RewriteMask { keep_elem: &keep, recolor_elem: &recolor, keep_src: &src },
        )
        .unwrap();

        let rescan = scan(&rewritten, &F).unwrap();
        assert_eq!(rescan.elems.len(), 1);
        assert_eq!(rescan.elems[0].weight, Some(7));
        assert_eq!(rescan.elems[0].colorful, Some(0));
        assert!(rescan.elems[0].has_content);
        let c_start = rescan.elems[0].content_start;
        let c_len = rescan.elems[0].content_len;
        assert_eq!(&rewritten[c_start..c_start + c_len], b"dup-color");
        assert!(rewritten.windows(16).any(|w| w == b"elem-unknown-mid"));
        assert!(rewritten.windows(4).any(|w| w == b"\xca\xfe\xba\xbe"));
        let tail = varint_field(7, 999);
        assert_eq!(&rewritten[rewritten.len() - tail.len()..], &tail[..]);
    }

    #[test]
    fn recolor_bit_on_elem_without_colorful_is_identity() {
        // 位图说改色但 elem 没有彩字字段：输出必须与输入逐字节相同。
        let reply = elem(Some(3), None, Some(b"text"));
        let keep = [1u8];
        let recolor = [1u8];
        let rewritten = rewrite(&reply, &F, &RewriteMask { keep_elem: &keep, recolor_elem: &recolor, keep_src: &[0u8; 0] }).unwrap();
        assert_eq!(rewritten, reply);
    }

    #[test]
    fn varint_edges_do_not_panic() {
        // 10 字节 varint（int32 负值的 protobuf 编码）。
        let mut body = Vec::new();
        body.extend(tag(F.weight_field, 0));
        body.extend_from_slice(&[0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01]);
        let reply = bytes_field(F.elems_field, &body);
        let out = scan(&reply, &F).unwrap();
        assert_eq!(out.elems[0].weight, Some(-1));
        // 非最小编码的长度前缀（0x81 0x00 = 1）。
        let mut body2 = Vec::new();
        body2.extend(tag(F.content_field, 2));
        body2.extend_from_slice(&[0x81, 0x00, b'x']);
        let reply2 = bytes_field(F.elems_field, &body2);
        let out2 = scan(&reply2, &F).unwrap();
        assert!(out2.elems[0].has_content);
        assert_eq!(out2.elems[0].content_len, 1);
        // 超大长度 varint（2^32+3）：必须拒绝；32 位平台上转 usize 会回绕成 3 而被误读。
        let mut body3 = Vec::new();
        body3.extend(tag(F.content_field, 2));
        body3.extend_from_slice(&[0x83, 0x80, 0x80, 0x80, 0x10]);
        let reply3 = bytes_field(F.elems_field, &body3);
        assert!(scan(&reply3, &F).is_err());
        // 空 content（len=0 且存在）。
        let reply4 = bytes_field(F.elems_field, &{
            let mut only = tag(F.content_field, 2);
            put_varint(&mut only, 0).unwrap();
            only
        });
        let out4 = scan(&reply4, &F).unwrap();
        assert!(out4.elems[0].has_content);
        assert_eq!(out4.elems[0].content_len, 0);
    }

    #[test]
    fn flatten_layout_matches_contract() {
        let mut reply = Vec::new();
        reply.extend(elem(Some(i32::MIN as u32 as u64), Some(60001), Some(b"t")));
        reply.extend(src(None));
        let out = scan(&reply, &F).unwrap();
        let flat = flatten(&out).unwrap();
        // elem: weight 存在且值为 i32::MIN（哨兵碰撞回归）；colorful 存在；content 存在。
        assert_eq!(flat.len(), 2 + 5 + 2);
        assert_eq!(flat[0], 1);
        assert_eq!(flat[1], 1);
        assert_eq!(flat[2], i32::MIN);
        assert_eq!(flat[3], 60001);
        assert_eq!(flat[4], 0b111);
        assert_eq!(flat[5], out.elems[0].content_start as i32);
        assert_eq!(flat[6], 1);
        // src: type 缺席 → flags=0、值 0。
        assert_eq!(flat[7], 0);
        assert_eq!(flat[8], 0);
    }

    #[test]
    fn elem_count_limits_are_enforced() {
        let mut reply = Vec::new();
        for _ in 0..=MAX_ELEMS {
            reply.extend(elem(None, None, None));
        }
        assert!(matches!(scan(&reply, &F), Err(WireError::TooManyElems)));
        let mut reply_ok = Vec::new();
        for _ in 0..100 {
            reply_ok.extend(elem(None, None, None));
        }
        let out = scan(&reply_ok, &F).unwrap();
        assert_eq!(out.elems.len(), 100);
    }

    #[test]
    fn arbitrary_bytes_never_panic_and_all_keep_rewrite_is_identity() {
        // 确定性伪模糊：LCG 生成的任意字节 + 从小池抽取的字段号组合。
        // 性质 1：scan/rewrite 永不 panic；性质 2：合法输入下全保留重写 = 逐字节同一。
        let mut seed: u64 = 0x1234_5678_9abc_def0;
        let mut next = || {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            seed
        };
        let pool = [1u32, 2, 4, 8, 10, 11, 16, 99];
        for _ in 0..20_000 {
            let len = (next() % 512) as usize;
            let buf: Vec<u8> = (0..len).map(|_| (next() & 0xff) as u8).collect();
            let mut pick = || pool[(next() % pool.len() as u64) as usize];
            let fields = WireFields {
                elems_field: pick(),
                weight_field: pick(),
                colorful_field: pick(),
                content_field: pick(),
                colorful_src_field: pick(),
                colorful_type_field: pick(),
            };
            if let Ok(out) = scan(&buf, &fields) {
                let keep = vec![1u8; out.elems.len()];
                let recolor = vec![0u8; out.elems.len()];
                let keep_src = vec![1u8; out.srcs.len()];
                let rewritten = rewrite(
                    &buf,
                    &fields,
                    &RewriteMask { keep_elem: &keep, recolor_elem: &recolor, keep_src: &keep_src },
                )
                .unwrap();
                assert_eq!(rewritten, buf);
            }
        }
    }

    #[test]
    fn concurrent_scan_rewrite_is_safe() {
        let mut reply = Vec::new();
        for i in 0..64u64 {
            reply.extend(elem(Some(i % 10 + 1), Some(60001), Some(b"txt")));
        }
        thread::scope(|scope| {
            let mut handles = Vec::new();
            for _ in 0..4 {
                handles.push(scope.spawn(|| {
                    for _ in 0..2_000 {
                        let out = scan(&reply, &F).unwrap();
                        let keep = vec![1u8; out.elems.len()];
                        let recolor = vec![0u8; out.elems.len()];
                        let keep_src = vec![1u8; out.srcs.len()];
                        let same = rewrite(
                            &reply,
                            &F,
                            &RewriteMask { keep_elem: &keep, recolor_elem: &recolor, keep_src: &keep_src },
                        )
                        .unwrap();
                        assert_eq!(same.len(), reply.len());
                    }
                }));
            }
            for handle in handles {
                handle.join().unwrap();
            }
        });
    }

    #[test]
    fn c_abi_roundtrip_handover_and_free() {
        let mut reply = Vec::new();
        reply.extend(elem(Some(7), Some(60001), Some(b"abc")));
        reply.extend(src(Some(60001)));
        // scan 全链路（含 catch_unwind 与 handover）。
        let mut out: *mut i32 = std::ptr::null_mut();
        let mut len: usize = 0;
        let mut cap: usize = 0;
        let code = unsafe { dw_scan(reply.as_ptr(), reply.len(), &F, &mut out, &mut len, &mut cap) };
        assert_eq!(code, DW_OK);
        assert!(!out.is_null());
        assert!(cap >= len);
        assert_eq!(unsafe { *out }, 1);
        unsafe { dw_free_i32(out, len, cap) };
        // 空指针参数 → ARGS（垫片从不传 null，这里钉死边界）。
        let code = unsafe { dw_scan(std::ptr::null(), 0, &F, &mut out, &mut len, &mut cap) };
        assert_eq!(code, DW_ERR_ARGS);
        let code = unsafe { dw_scan(reply.as_ptr(), reply.len(), &F, &mut out, &mut len, std::ptr::null_mut()) };
        assert_eq!(code, DW_ERR_ARGS);
        // rewrite 全链路：全保留 + 改色。
        let keep = [1u8];
        let recolor = [1u8];
        let keep_src = [0u8; 1];
        let mut bytes_out: *mut u8 = std::ptr::null_mut();
        let mut bytes_len: usize = 0;
        let mut bytes_cap: usize = 0;
        let code = unsafe {
            dw_rewrite(
                reply.as_ptr(), reply.len(), &F,
                keep.as_ptr(), keep.len(),
                recolor.as_ptr(), recolor.len(),
                keep_src.as_ptr(), keep_src.len(),
                &mut bytes_out, &mut bytes_len, &mut bytes_cap,
            )
        };
        assert_eq!(code, DW_OK);
        assert!(!bytes_out.is_null());
        assert!(bytes_cap >= bytes_len);
        let rewritten = unsafe { std::slice::from_raw_parts(bytes_out, bytes_len) }.to_vec();
        unsafe { dw_free_u8(bytes_out, bytes_len, bytes_cap) };
        let rescan = scan(&rewritten, &F).unwrap();
        assert_eq!(rescan.elems.len(), 1);
        assert_eq!(rescan.elems[0].colorful, Some(0));
        assert_eq!(rescan.srcs.len(), 0);
        // 零长度但非空的合法输入：scan 成功（0 元素），handover 空缓冲返回 (null, 0, 0)。
        let dangling = std::ptr::NonNull::<u8>::dangling().as_ptr();
        let code = unsafe { dw_scan(dangling, 0, &F, &mut out, &mut len, &mut cap) };
        assert_eq!(code, DW_OK);
        assert_eq!(unsafe { *out }, 0);
        assert_eq!(len, 2);
        assert!(cap >= len);
        unsafe { dw_free_i32(out, len, cap) };
    }

    #[test]
    fn handover_returns_null_for_empty() {
        assert_eq!(handover_u8(Vec::new()), (std::ptr::null_mut(), 0, 0));
        assert_eq!(handover_u8(Vec::with_capacity(8)), (std::ptr::null_mut(), 0, 0));
        assert_eq!(handover_i32(Vec::new()), (std::ptr::null_mut(), 0, 0));
        // len < cap 时原样保留 capacity 并正确释放，不发生 shrink 重分配。
        let mut spare = Vec::with_capacity(16);
        spare.extend_from_slice(&[1u8, 2, 3]);
        let (ptr, len, cap) = handover_u8(spare);
        assert!(!ptr.is_null());
        assert_eq!(len, 3);
        assert_eq!(cap, 16);
        unsafe { dw_free_u8(ptr, len, cap) };
        // cap==0 的 free 是无操作（包括对悬垂指针）。
        unsafe {
            dw_free_u8(std::ptr::NonNull::<u8>::dangling().as_ptr(), 0, 0);
            dw_free_i32(std::ptr::NonNull::<i32>::dangling().as_ptr(), 0, 0);
        }
    }

    #[test]
    fn rejects_oversized_and_mismatched_masks_and_handles_elem_src_and_reply_limits() {
        // 1. 超过 MAX_REPLY_BYTES 的载荷必须在 scan/rewrite/C-ABI 三条路径安全拒绝。
        let too_large = vec![0u8; MAX_REPLY_BYTES + 1];
        assert_eq!(scan(&too_large, &F), Err(WireError::TooLarge));
        let empty_mask = RewriteMask { keep_elem: &[], recolor_elem: &[], keep_src: &[] };
        assert_eq!(rewrite(&too_large, &F, &empty_mask), Err(WireError::TooLarge));

        let mut scan_out: *mut i32 = std::ptr::null_mut();
        let mut scan_len: usize = 0;
        let mut scan_cap: usize = 0;
        assert_eq!(
            unsafe { dw_scan(too_large.as_ptr(), too_large.len(), &F, &mut scan_out, &mut scan_len, &mut scan_cap) },
            DW_ERR_DECLINE
        );
        assert!(scan_out.is_null());

        let dangling = std::ptr::NonNull::<u8>::dangling().as_ptr();
        let mut rw_out: *mut u8 = std::ptr::null_mut();
        let mut rw_len: usize = 0;
        let mut rw_cap: usize = 0;
        assert_eq!(
            unsafe {
                dw_rewrite(
                    too_large.as_ptr(), too_large.len(), &F,
                    dangling, 0, dangling, 0, dangling, 0,
                    &mut rw_out, &mut rw_len, &mut rw_cap,
                )
            },
            DW_ERR_DECLINE
        );
        assert!(rw_out.is_null());

        // 2. 掩码长度不一致 / 超过 MAX_ELEMS / 超过 MAX_SRCS 在解析前直接拒绝。
        assert_eq!(
            rewrite(&[], &F, &RewriteMask { keep_elem: &[1], recolor_elem: &[], keep_src: &[] }),
            Err(WireError::MaskLength)
        );
        let max_elems_plus_one = vec![1u8; MAX_ELEMS + 1];
        assert_eq!(
            rewrite(
                &[], &F,
                &RewriteMask {
                    keep_elem: &max_elems_plus_one,
                    recolor_elem: &max_elems_plus_one,
                    keep_src: &[],
                }
            ),
            Err(WireError::TooManyElems)
        );
        let max_srcs_plus_one = vec![1u8; MAX_SRCS + 1];
        assert_eq!(
            rewrite(
                &[], &F,
                &RewriteMask {
                    keep_elem: &[],
                    recolor_elem: &[],
                    keep_src: &max_srcs_plus_one,
                }
            ),
            Err(WireError::TooManySrcs)
        );
        assert_eq!(
            unsafe {
                dw_rewrite(
                    dangling, 0, &F,
                    max_elems_plus_one.as_ptr(), max_elems_plus_one.len(),
                    max_elems_plus_one.as_ptr(), max_elems_plus_one.len(),
                    dangling, 0,
                    &mut rw_out, &mut rw_len, &mut rw_cap,
                )
            },
            DW_ERR_DECLINE
        );

        // 3. dw_rewrite 空输入 + 空掩码合法返回 DW_OK 且 handover 为 (null, 0, 0)。
        assert_eq!(
            unsafe {
                dw_rewrite(
                    dangling, 0, &F,
                    dangling, 0, dangling, 0, dangling, 0,
                    &mut rw_out, &mut rw_len, &mut rw_cap,
                )
            },
            DW_OK
        );
        assert!(rw_out.is_null());
        assert_eq!(rw_len, 0);
        assert_eq!(rw_cap, 0);

        // 4. MAX_ELEMS 精确边界通过，MAX_ELEMS + 1 拒绝；MAX_SRCS 精确边界通过，MAX_SRCS + 1 拒绝。
        let one_empty_elem = bytes_field(F.elems_field, &[]);
        let mut max_elems_reply = Vec::with_capacity(one_empty_elem.len() * (MAX_ELEMS + 1));
        for _ in 0..MAX_ELEMS {
            max_elems_reply.extend_from_slice(&one_empty_elem);
        }
        assert_eq!(scan(&max_elems_reply, &F).unwrap().elems.len(), MAX_ELEMS);
        let keep_all_elems = vec![1u8; MAX_ELEMS];
        let recolor_none_elems = vec![0u8; MAX_ELEMS];
        assert_eq!(
            rewrite(
                &max_elems_reply,
                &F,
                &RewriteMask {
                    keep_elem: &keep_all_elems,
                    recolor_elem: &recolor_none_elems,
                    keep_src: &[],
                },
            )
            .unwrap()
            .len(),
            max_elems_reply.len()
        );
        max_elems_reply.extend_from_slice(&one_empty_elem);
        assert_eq!(scan(&max_elems_reply, &F), Err(WireError::TooManyElems));

        let one_empty_src = bytes_field(F.colorful_src_field, &[]);
        let mut max_srcs_reply = Vec::with_capacity(one_empty_src.len() * (MAX_SRCS + 1));
        for _ in 0..MAX_SRCS {
            max_srcs_reply.extend_from_slice(&one_empty_src);
        }
        assert_eq!(scan(&max_srcs_reply, &F).unwrap().srcs.len(), MAX_SRCS);
        let keep_all_srcs = vec![1u8; MAX_SRCS];
        assert_eq!(
            rewrite(
                &max_srcs_reply,
                &F,
                &RewriteMask { keep_elem: &[], recolor_elem: &[], keep_src: &keep_all_srcs },
            )
            .unwrap()
            .len(),
            max_srcs_reply.len()
        );
        max_srcs_reply.extend_from_slice(&one_empty_src);
        assert_eq!(scan(&max_srcs_reply, &F), Err(WireError::TooManySrcs));

        // 5. 多字节 tag 的彩字字段改色时保留多字节 tag 原样并归零值字节。
        let high_fields = WireFields { colorful_field: 200, ..F };
        let high_elem = {
            let mut body = Vec::new();
            body.extend(varint_field(high_fields.colorful_field, 60001));
            bytes_field(high_fields.elems_field, &body)
        };
        let rewritten = rewrite(
            &high_elem,
            &high_fields,
            &RewriteMask { keep_elem: &[1], recolor_elem: &[1], keep_src: &[] },
        )
        .unwrap();
        let rescanned = scan(&rewritten, &high_fields).unwrap();
        assert_eq!(rescanned.elems.len(), 1);
        assert_eq!(rescanned.elems[0].colorful, Some(0));
    }
}
