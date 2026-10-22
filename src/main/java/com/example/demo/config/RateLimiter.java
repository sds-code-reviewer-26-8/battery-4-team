package com.example.demo.config;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** 키(클라이언트 주소)별로 일정 시간 안의 요청 횟수를 제한한다. 서버 메모리에만 두므로 재시작하면 초기화된다. */
public class RateLimiter {

	/** 기록한 키가 이 수를 넘으면 시간이 지난 키부터 비운다. */
	private static final int SWEEP_THRESHOLD = 10_000;

	private final int maxRequests;
	private final long windowMillis;
	private final LongSupplier clock;
	private final ConcurrentHashMap<String, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();

	public RateLimiter(int maxRequests, Duration window) {
		this(maxRequests, window, System::currentTimeMillis);
	}

	RateLimiter(int maxRequests, Duration window, LongSupplier clock) {
		this.maxRequests = maxRequests;
		this.windowMillis = window.toMillis();
		this.clock = clock;
	}

	/** 한도 안이면 이번 요청을 기록하고 true, 넘으면 기록하지 않고 false */
	public boolean tryAcquire(String key) {
		long now = clock.getAsLong();
		if (hits.size() > SWEEP_THRESHOLD) {
			sweep(now);
		}
		boolean[] allowed = new boolean[1];
		hits.compute(key, (k, times) -> {
			ArrayDeque<Long> recent = prune(times, now);
			if (recent.size() < maxRequests) {
				recent.addLast(now);
				allowed[0] = true;
			}
			return recent;
		});
		return allowed[0];
	}

	/** 한도를 넘었을 때 다시 시도할 수 있기까지 남은 초(올림, 최소 1) */
	public long retryAfterSeconds(String key) {
		Long[] oldest = new Long[1];
		hits.computeIfPresent(key, (k, times) -> {
			oldest[0] = times.peekFirst();
			return times;
		});
		if (oldest[0] == null) {
			return 1;
		}
		long waitMillis = oldest[0] + windowMillis - clock.getAsLong();
		return Math.max(1, (waitMillis + 999) / 1000);
	}

	private ArrayDeque<Long> prune(ArrayDeque<Long> times, long now) {
		ArrayDeque<Long> recent = times != null ? times : new ArrayDeque<>();
		while (!recent.isEmpty() && now - recent.peekFirst() >= windowMillis) {
			recent.pollFirst();
		}
		return recent;
	}

	private void sweep(long now) {
		for (String key : hits.keySet()) {
			hits.computeIfPresent(key, (k, times) -> {
				ArrayDeque<Long> recent = prune(times, now);
				return recent.isEmpty() ? null : recent;
			});
		}
	}
}
