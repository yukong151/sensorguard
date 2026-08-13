use once_cell::sync::OnceCell;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};

#[repr(u8)]
#[derive(Copy, Clone, PartialEq, Debug)]
pub enum Health {
    Ok = 0,
    Degraded = 1,
    SafeMode = 2,
    Dead = 3,
}

impl Health {
    /// 从 u8 原始值还原为 Health 枚举。无效值返回 Dead(安全侧倒)。
    pub fn from_u8(v: u8) -> Self {
        match v {
            0 => Health::Ok,
            1 => Health::Degraded,
            2 => Health::SafeMode,
            _ => Health::Dead,
        }
    }
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

    /// 进入降级状态(Ok → Degraded)。
    /// 仅在当前为 Ok 时转换;已处于 Degraded/SafeMode/Dead 则保持不变(不可逆降级无意义)。
    pub fn enter_degraded(&self) {
        let _ = self.health.compare_exchange(
            Health::Ok as u8,
            Health::Degraded as u8,
            Ordering::Release,
            Ordering::Relaxed,
        );
    }

    /// 进入 SafeMode(任意非 Dead 状态 → SafeMode)。
    /// Dead 是终态,不可恢复。使用 CAS 循环避免与 mark_dead 的竞态。
    pub fn enter_safe_mode(&self) {
        loop {
            let cur = self.health.load(Ordering::Acquire);
            if cur == Health::Dead as u8 {
                return;
            }
            if self.health.compare_exchange(
                cur,
                Health::SafeMode as u8,
                Ordering::Release,
                Ordering::Relaxed,
            ).is_ok() {
                return;
            }
        }
    }

    /// 标记死亡(任意状态 → Dead)。Dead 是终态,不可恢复。
    pub fn mark_dead(&self) {
        self.health.store(Health::Dead as u8, Ordering::Release);
        self.ready.store(false, Ordering::Release);
    }

    /// 尝试从 Degraded 恢复到 Ok(自愈)。
    /// 仅当当前为 Degraded 时生效;SafeMode/Dead 不可通过此方法恢复。
    pub fn recover_from_degraded(&self) {
        let _ = self.health.compare_exchange(
            Health::Degraded as u8,
            Health::Ok as u8,
            Ordering::Release,
            Ordering::Relaxed,
        );
    }

    /// 返回当前健康状态的 u8 编码(与 Health 枚举值对应)。
    pub fn health(&self) -> u8 {
        self.health.load(Ordering::Acquire)
    }

    /// 返回当前 Health 枚举(便于模式匹配)。
    pub fn health_enum(&self) -> Health {
        Health::from_u8(self.health.load(Ordering::Acquire))
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ok_to_degraded() {
        let s = State::new();
        assert_eq!(s.health_enum(), Health::Ok);
        s.enter_degraded();
        assert_eq!(s.health_enum(), Health::Degraded);
    }

    #[test]
    fn degraded_recovers_to_ok() {
        let s = State::new();
        s.enter_degraded();
        s.recover_from_degraded();
        assert_eq!(s.health_enum(), Health::Ok);
    }

    #[test]
    fn safe_mode_does_not_override_dead() {
        let s = State::new();
        s.mark_dead();
        s.enter_safe_mode();
        assert_eq!(s.health_enum(), Health::Dead, "Dead 不可被 SafeMode 覆盖");
    }

    #[test]
    fn mark_dead_is_terminal() {
        let s = State::new();
        s.enter_degraded();
        s.mark_dead();
        assert_eq!(s.health_enum(), Health::Dead);
        // Dead 后任何恢复尝试均无效
        s.recover_from_degraded();
        assert_eq!(s.health_enum(), Health::Dead);
        s.enter_degraded();
        assert_eq!(s.health_enum(), Health::Dead);
    }

    #[test]
    fn mark_dead_clears_ready() {
        let s = State::new();
        s.mark_ready();
        assert!(s.is_ready());
        s.mark_dead();
        assert!(!s.is_ready(), "Dead 状态 ready 必须为 false");
    }

    #[test]
    fn enter_degraded_idempotent_on_higher_states() {
        let s = State::new();
        s.enter_safe_mode();
        s.enter_degraded(); // SafeMode 不应降级回 Degraded
        assert_eq!(s.health_enum(), Health::SafeMode);
    }

    #[test]
    fn health_from_u8_invalid_defaults_to_dead() {
        assert_eq!(Health::from_u8(99), Health::Dead);
        assert_eq!(Health::from_u8(0), Health::Ok);
        assert_eq!(Health::from_u8(1), Health::Degraded);
        assert_eq!(Health::from_u8(2), Health::SafeMode);
        assert_eq!(Health::from_u8(3), Health::Dead);
    }
}
