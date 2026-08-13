//! W2~W3 (文档 §5.3 / §13):Mutex + Vec 占位换为真正 lock-free SPSC 环形缓冲。
//! 生产者 = Sensor 回调(单线程),消费者 = sg_tick(单线程)。
//! 无锁、无分配、固定容量 4096(2^12);槽位数据通过 Release/Acquire 原子序同步可见性。
//! 内存布局:Box 内 UnsafeCell 数组 + head/tail 两个 AtomicUsize。

use std::cell::UnsafeCell;
use std::mem::MaybeUninit;
use std::sync::atomic::{AtomicUsize, Ordering};

#[derive(Copy, Clone)]
pub struct Sample {
    pub ts_ns: i64,
    pub kind: u8,
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

const CAP: usize = 4096;
const MASK: usize = CAP - 1;

pub struct Ring {
    buf: Box<[UnsafeCell<MaybeUninit<Sample>>]>,
    /// 消费者侧:下一个可读槽位
    head: AtomicUsize,
    /// 生产者侧:下一个可写入槽位
    tail: AtomicUsize,
}

impl Ring {
    pub fn new() -> Self {
        let mut buf = Vec::with_capacity(CAP);
        for _ in 0..CAP {
            buf.push(UnsafeCell::new(MaybeUninit::uninit()));
        }
        Self {
            buf: buf.into_boxed_slice(),
            head: AtomicUsize::new(0),
            tail: AtomicUsize::new(0),
        }
    }
    // Deviation(doc-frozen): clippy::new_without_default gate. Purely additive; contract unchanged.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
    /// 入队满则返回 Err(不覆盖、不阻塞)。生产者侧,仅 Sensor 回调线程调用。
    // Deviation(doc-frozen): clippy::result_unit_err gate. Signature per §4.2 contract; `()` error carries no payload.
    #[allow(clippy::result_unit_err)]
    pub fn push(&self, s: Sample) -> Result<(), ()> {
        let t = self.tail.load(Ordering::Relaxed);
        let h = self.head.load(Ordering::Acquire);
        if t.wrapping_sub(h) >= CAP {
            return Err(());
        }
        // 安全:槽位仅当前生产者写入;同一槽位复用必须等消费者 head 推进
        // (push 上方 Acquire 读到的最新 head 即保证此前消费已完成)。
        unsafe {
            (*self.buf[t & MASK].get()).write(s);
        }
        // Release:写入先于 tail 发布,消费者 Acquire 读 tail 后可见完整 Sample。
        self.tail.store(t.wrapping_add(1), Ordering::Release);
        Ok(())
    }
    /// 出队一个样本,空返回 None。消费者侧,仅 sg_tick 调用。
    pub fn pop(&self) -> Option<Sample> {
        let h = self.head.load(Ordering::Relaxed);
        let t = self.tail.load(Ordering::Acquire);
        if h == t {
            return None;
        }
        // 安全:tail 已推进 ⇒ 对应槽位已被生产者完整写入;该槽位接下来
        // 由消费者独占读取,生产者不会在 head 推进前复用。
        let s = unsafe { (*self.buf[h & MASK].get()).assume_init_read() };
        self.head.store(h.wrapping_add(1), Ordering::Release);
        Some(s)
    }
    /// 当前在队元素数(tail - head,无符号取差)。
    pub fn len(&self) -> usize {
        let h = self.head.load(Ordering::Acquire);
        let t = self.tail.load(Ordering::Acquire);
        t.wrapping_sub(h)
    }

    /// 环形缓冲总容量(固定 4096,文档 §9 性能预算)。
    pub fn capacity(&self) -> usize {
        CAP
    }
}

// Deviation(doc-frozen): clippy::new_without_default gate. Purely additive; contract unchanged.
impl Default for Ring {
    fn default() -> Self {
        Self::new()
    }
}

// 单生产者 + 单消费者时 lock-free;不同槽位经完整 acquire/release 链同步,
// 不依赖锁。多生产者/多消费者属于未定义使用场景(文档定义 SPSC)。
unsafe impl Sync for Ring {}

use once_cell::sync::Lazy;
pub static RING: Lazy<Ring> = Lazy::new(Ring::new);

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    fn s(ts: i64, v: f32) -> Sample {
        Sample {
            ts_ns: ts,
            kind: 10,
            x: v,
            y: v + 1.0,
            z: v + 2.0,
        }
    }

    #[test]
    fn fifo_roundtrip() {
        let r = Ring::new();
        assert!(r.is_empty());
        assert!(r.push(s(1, 0.0)).is_ok());
        assert!(r.push(s(2, 1.0)).is_ok());
        assert_eq!(r.len(), 2);
        let a = r.pop().expect("a");
        let b = r.pop().expect("b");
        assert_eq!(a.ts_ns, 1);
        assert_eq!(b.ts_ns, 2);
        assert_eq!(b.y, 2.0);
        assert!(r.is_empty());
        assert!(r.pop().is_none());
    }

    #[test]
    fn full_returns_err_without_evict() {
        let r = Ring::new();
        for i in 0..CAP as i64 {
            assert!(r.push(s(i, 0.0)).is_ok(), "slot {i} should accept");
        }
        assert_eq!(r.len(), CAP);
        assert!(r.push(s(-1, 0.0)).is_err(), "full ring rejects");
        assert_eq!(r.len(), CAP, "no eviction on push");
        // 消费一个后可再入
        let _ = r.pop();
        assert!(r.push(s(-2, 0.0)).is_ok());
    }

    #[test]
    fn wrap_around_reuses_slots() {
        let r = Ring::new();
        for i in 0..CAP as i64 {
            assert!(r.push(s(i, 0.0)).is_ok());
        }
        for i in 0..CAP as i64 {
            assert_eq!(r.pop().map(|x| x.ts_ns), Some(i));
        }
        // 重新写满一轮,验证索引回绕无错位
        for i in CAP as i64..(CAP as i64 * 2) {
            assert!(r.push(s(i, 0.0)).is_ok());
        }
        for i in CAP as i64..(CAP as i64 * 2) {
            assert_eq!(r.pop().map(|x| x.ts_ns), Some(i));
        }
    }

    #[test]
    fn spsc_concurrent_preserves_fifo() {
        let r = Arc::new(Ring::new());
        const N: usize = 100_000;
        let prod = r.clone();
        let t = thread::spawn(move || {
            for i in 0..N {
                loop {
                    if prod.push(s(i as i64, 0.0)).is_ok() {
                        break;
                    }
                    thread::yield_now();
                }
            }
        });
        let mut expected = 0usize;
        let mut got = 0usize;
        while got < N {
            if let Some(x) = r.pop() {
                assert_eq!(x.ts_ns as usize, expected, "FIFO order violated");
                expected += 1;
                got += 1;
            } else {
                thread::yield_now();
            }
        }
        t.join().expect("producer panicked");
        assert_eq!(expected, N);
    }
}
