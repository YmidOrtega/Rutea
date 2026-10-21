package me.ymid.rutea.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SimulationClockTest {

    private final AtomicLong wall = new AtomicLong();
    private final SimulationClock clock = new SimulationClock(wall::get, 60, 3600);

    private void advance(double wallSeconds) {
        wall.addAndGet((long) (wallSeconds * 1e9));
    }

    @Test
    void startsPausedAtTheBeginningOfTheShift() {
        advance(10);

        assertThat(clock.seconds()).isZero();
        assertThat(clock.state().running()).isFalse();
    }

    @Test
    void runsAtItsSpeedAndHoldsStillWhilePaused() {
        clock.play();
        advance(2);
        assertThat(clock.seconds()).isEqualTo(120);

        clock.pause();
        advance(5);
        assertThat(clock.seconds()).isEqualTo(120);

        clock.play();
        advance(1);
        assertThat(clock.seconds()).isEqualTo(180);
    }

    @Test
    void changingSpeedKeepsTheTimeAlreadyRun() {
        clock.play();
        advance(1);
        clock.speed(240);
        advance(1);

        assertThat(clock.seconds()).isEqualTo(300);
    }

    @Test
    void stopsAtTheEndOfTheShiftAndOnlyARestartRunsItAgain() {
        clock.play();
        advance(100);

        assertThat(clock.seconds()).isEqualTo(3600);
        assertThat(clock.finished()).isTrue();
        clock.play();
        assertThat(clock.state().running()).isFalse();

        clock.restart();
        advance(1);
        assertThat(clock.seconds()).isEqualTo(60);
    }

    @Test
    void aLongerShiftLetsAFinishedClockRunOn() {
        clock.play();
        advance(100);
        clock.end(7200);
        clock.play();
        advance(1);

        assertThat(clock.seconds()).isEqualTo(3660);
    }

    @Test
    void refusesANonPositiveSpeed() {
        assertThatThrownBy(() -> clock.speed(0)).hasMessageContaining("positiva");
    }
}
