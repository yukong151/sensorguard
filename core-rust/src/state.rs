use once_cell::sync::OnceCell;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};

#[repr(u8)]
#[derive(Copy, Clone, PartialEq)]
pub enum Health {
    Ok = 0,
    Degraded = 1,
    SafeMode = 2,
    Dead = 3,
}

pub struct State {
    ready: AtomicBool,
    health: AtomicU8,
}

impl State {
    pub const fn new() -> Self {
        Self {
            ready: AtomicBool::new(false),
            health: AtomicU8::new(Health::Ok as u8),
        }
    }
    pub fn mark_ready(&self) {
        self.ready.store(true, Ordering::Release);
    }
    pub fn is_ready(&self) -> bool {
        self.ready.load(Ordering::Acquire)
    }
    pub fn enter_safe_mode(&self) {
        self.health.store(Health::SafeMode as u8, Ordering::Release);
    }
    pub fn health(&self) -> u8 {
        self.health.load(Ordering::Acquire)
    }
    pub fn shutdown(&self) {
        self.ready.store(false, Ordering::Release);
    }
}

// Deviation(doc-frozen): clippy::new_without_default gate. Purely additive; contract unchanged.
impl Default for State {
    fn default() -> Self {
        Self::new()
    }
}

static STATE_CELL: OnceCell<State> = OnceCell::new();
pub fn state() -> &'static State {
    STATE_CELL.get_or_init(State::new)
}
