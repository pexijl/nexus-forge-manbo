package com.nexusforge.provider.support;

import com.nexusforge.config.AiProperties;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** 简单计数滑窗熔断器。线程安全(内部锁)。 */
public class CircuitState {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final AiProperties.CircuitBreaker cfg;
    private final Deque<Instant> failures = new ArrayDeque<>();
    private State state = State.CLOSED;
    private Instant openedAt;

    public CircuitState(AiProperties.CircuitBreaker cfg) {
        this.cfg = cfg;
    }

    public synchronized boolean isOpen() {
        if (state == State.OPEN) {
            if (Instant.now().isAfter(openedAt.plus(cfg.getHalfOpenAfter()))) {
                state = State.HALF_OPEN;
                return false;
            }
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess() {
        failures.clear();
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        Instant now = Instant.now();
        failures.addLast(now);
        // 滑窗外的老失败丢弃
        Instant cutoff = now.minus(cfg.getWindowSize());
        while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
            failures.pollFirst();
        }
        if (state == State.HALF_OPEN || failures.size() >= cfg.getFailureThreshold()) {
            state = State.OPEN;
            openedAt = now;
        }
    }
}