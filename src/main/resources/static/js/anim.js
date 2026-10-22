/**
 * 지도 애니메이션. 주문 추가(시뮬레이션 결과)와 주문 상세(저장된 계획)에서 같은 방식으로 재생한다.
 * stored = {master, orders, visits, plan}. 시각 t 는 주문일(0일차)부터 센 일수(소수 가능).
 */
const BatteryAnim = (function () {
	const MAP_W = 1536;
	const MAP_H = 1024;
	// map.png 위의 공장 아이콘 중심 / 부두(배가 정박하는 곳) 좌표(px)
	const ICON = {KOREA: [925, 135], CHINA: [670, 165], JAPAN: [1108, 218], VIETNAM: [370, 465], INDONESIA: [515, 910]};
	const DOCK = {KOREA: [962, 168], CHINA: [697, 197], JAPAN: [1137, 246], VIETNAM: [398, 497], INDONESIA: [542, 942]};
	const BASE_SPEED = 2; // 일/초

	const pct = (v, total) => (v / total * 100) + '%';

	function mount(root, initial, opts) {
		opts = opts || {};
		root.innerHTML = `
			<div class="anim">
				<div class="anim-stage">
					<img class="anim-map" src="/images/map.png" alt="지도" draggable="false">
					<svg class="anim-routes" viewBox="0 0 ${MAP_W} ${MAP_H}" preserveAspectRatio="none"></svg>
					<div class="anim-badge">
						<div>총 비용 <b data-cost>0.00</b>억원</div>
						<div>한국 입고 <b data-inbound>0</b>K</div>
					</div>
				</div>
				<div class="anim-controls">
					<button type="button" data-play class="primary">재생</button>
					<button type="button" data-restart>처음부터</button>
					<button type="button" data-speed>2배속</button>
					<input type="range" data-slider min="0" max="1" step="0.01" value="0">
					<span class="anim-day" data-day></span>
				</div>
			</div>`;
		const stage = root.querySelector('.anim-stage');
		const routesEl = root.querySelector('.anim-routes');
		const costEl = root.querySelector('[data-cost]');
		const inboundEl = root.querySelector('[data-inbound]');
		const playBtn = root.querySelector('[data-play]');
		const speedBtn = root.querySelector('[data-speed]');
		const slider = root.querySelector('[data-slider]');
		const dayEl = root.querySelector('[data-day]');

		const portEls = {};
		for (const c of Object.keys(ICON)) {
			const d = document.createElement('div');
			d.className = 'port';
			d.style.left = pct(ICON[c][0], MAP_W);
			d.style.top = pct(ICON[c][1], MAP_H);
			d.innerHTML = `<span class="pname">${COUNTRY_LABEL[c]}</span>` +
				(c === 'KOREA' ? '' : '<span class="pstock">0</span>');
			stage.appendChild(d);
			portEls[c] = d.querySelector('.pstock');
		}

		let stored = null;
		let ships = [];
		let shipEls = [];
		let total = 0;
		let t = 0;
		let playing = false;
		let fast = false;
		let last = null;

		/** 이 계획에서 배가 실제로 지나는 항로를 점선으로 그린다(같은 구간은 한 번만) */
		function drawRoutes(plan) {
			const seen = new Set();
			let lines = '';
			for (const sh of plan.ships) {
				for (const trip of sh.trips) {
					const stops = ['KOREA'].concat(trip.stops.map(s => s.country), ['KOREA']);
					for (let i = 0; i < stops.length - 1; i++) {
						const key = [stops[i], stops[i + 1]].sort().join('-');
						if (seen.has(key)) continue;
						seen.add(key);
						const a = DOCK[stops[i]];
						const b = DOCK[stops[i + 1]];
						lines += `<line x1="${a[0]}" y1="${a[1]}" x2="${b[0]}" y2="${b[1]}"/>`;
					}
				}
			}
			routesEl.innerHTML = lines;
		}

		function setStored(s, autoplay) {
			stored = s;
			total = s.plan.totalDays;
			drawRoutes(s.plan);
			shipEls.forEach(e => e.remove());
			ships = s.plan.ships;
			shipEls = ships.map(sh => {
				const d = document.createElement('div');
				d.className = 'ship';
				d.innerHTML = '<div class="ship-label"></div><div class="ship-img"></div>';
				stage.appendChild(d);
				return d;
			});
			slider.max = total;
			t = 0;
			render();
			setPlaying(autoplay !== false);
		}

		function setPlaying(p) {
			playing = p && total > 0;
			last = null;
			playBtn.textContent = playing ? '일시정지' : '재생';
			if (playing) requestAnimationFrame(tick);
		}

		function tick(now) {
			if (!playing || !root.isConnected) return;
			if (last !== null) {
				t = Math.min(total, t + (now - last) / 1000 * BASE_SPEED * (fast ? 2 : 1));
			}
			last = now;
			render();
			if (t >= total) {
				setPlaying(false);
				return;
			}
			requestAnimationFrame(tick);
		}

		// ---- 상태 계산

		function stockAt(country, time) {
			const st = stored.plan.stock[country];
			const pr = stored.plan.production[country];
			if (!st) return 0;
			const i = Math.min(Math.floor(time), st.length - 1);
			const grow = (pr && i < pr.length) ? pr[i] * (time - i) : 0;
			return st[i] + grow;
		}

		function cumProduction(country, time) {
			const pr = stored.plan.production[country];
			if (!pr) return 0;
			const i = Math.min(Math.floor(time), pr.length);
			let sum = 0;
			for (let d = 0; d < i; d++) sum += pr[d];
			if (i < pr.length) sum += pr[i] * (time - i);
			return sum;
		}

		function costAt(time) {
			let won = 0;
			for (const f of stored.master.factories) {
				won += cumProduction(f.country, time) * 1000 * f.unitCost;
			}
			for (const sh of stored.plan.ships) {
				for (const trip of sh.trips) {
					won += Math.max(0, Math.min(time, trip.returnDay) - trip.departDay) * stored.master.shipDailyCostWon;
				}
			}
			// 공장 운영비: 주문일부터 그 공장의 마지막 수거일까지 하루씩 쌓인다(운영비 도입 전 계획에는 없다)
			const perDay = {};
			stored.master.factories.forEach(f => perDay[f.country] = f.dailyOperatingCostWon || 0);
			for (const o of stored.plan.factoryOperations || []) {
				won += Math.min(time, o.days) * perDay[o.country];
			}
			return won;
		}

		function inboundAt(time) {
			let sum = 0;
			for (const sh of stored.plan.ships) {
				for (const trip of sh.trips) {
					if (trip.returnDay <= time) sum += trip.stops.reduce((a, s) => a + s.loadK, 0);
				}
			}
			return sum;
		}

		/** 배의 위치(부두 좌표, px), 진행 방향, 실은 양(K) */
		function shipAt(ship, time) {
			let pos = DOCK.KOREA;
			let dir = 1;
			let load = 0;
			for (const trip of ship.trips) {
				if (time < trip.departDay || time >= trip.returnDay) continue;
				const pts = [{c: 'KOREA', from: trip.departDay, to: trip.departDay}];
				for (const s of trip.stops) pts.push({c: s.country, from: s.leaveDay, to: s.arriveDay});
				pts.push({c: 'KOREA', from: trip.returnDay, to: trip.returnDay});
				for (let i = 0; i < pts.length - 1; i++) {
					const a = pts[i];
					const b = pts[i + 1];
					const t0 = a.from;
					const t1 = b.to;
					if (time >= t0 && time <= t1) {
						const f = t1 === t0 ? 1 : (time - t0) / (t1 - t0);
						const pa = DOCK[a.c];
						const pb = DOCK[b.c];
						pos = [pa[0] + (pb[0] - pa[0]) * f, pa[1] + (pb[1] - pa[1]) * f];
						dir = pb[0] >= pa[0] ? 1 : -1;
						break;
					}
				}
				load = trip.stops.filter(s => s.arriveDay <= time).reduce((a, s) => a + s.loadK, 0);
				break;
			}
			return {pos, dir, load};
		}

		function render() {
			for (const c of FACTORY_ORDER) {
				if (portEls[c]) portEls[c].textContent = fmt(Math.round(stockAt(c, t)));
			}
			ships.forEach((sh, i) => {
				const s = shipAt(sh, t);
				const el = shipEls[i];
				el.style.left = pct(s.pos[0], MAP_W);
				el.style.top = pct(s.pos[1], MAP_H);
				// 같은 한국 부두에 있는 배가 겹치지 않도록 호수별로 살짝 비켜 놓는다
				el.style.marginTop = (i * 22 - 20) + 'px';
				el.querySelector('.ship-img').style.transform = s.dir < 0 ? 'scaleX(-1)' : '';
				el.querySelector('.ship-label').textContent = `${sh.no}호 · ${fmt(s.load)}`;
			});
			costEl.textContent = eok(costAt(t));
			inboundEl.textContent = fmt(inboundAt(t));
			slider.value = t;
			dayEl.textContent = `${Math.floor(t)}일 / ${total}일`;
		}

		playBtn.addEventListener('click', () => {
			if (!playing && t >= total) t = 0;
			setPlaying(!playing);
		});
		root.querySelector('[data-restart]').addEventListener('click', () => {
			t = 0;
			render();
			setPlaying(true);
		});
		speedBtn.addEventListener('click', () => {
			fast = !fast;
			speedBtn.classList.toggle('active', fast);
		});
		slider.addEventListener('input', () => {
			t = parseFloat(slider.value);
			last = null;
			render();
		});

		setStored(initial, opts.autoplay);
		return {update: (s, autoplay) => setStored(s, autoplay)};
	}

	return {mount};
})();
