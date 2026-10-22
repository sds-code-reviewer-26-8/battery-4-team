(function () {
	const totalInput = document.getElementById('total');
	const gaugesEl = document.getElementById('gauges');
	const inputError = document.getElementById('inputError');
	const runBtn = document.getElementById('runBtn');
	const resultEl = document.getElementById('result');
	const optionsEl = document.getElementById('options');
	const confirmBtn = document.getElementById('confirmBtn');
	const confirmError = document.getElementById('confirmError');

	let master = null;                 // 게이지 옆 방문 횟수 표시용
	let values = {};                   // 나라별 주문 수량(K)
	let total = parseInt(totalInput.value, 10) || 12000;
	let result = null;                 // 마지막 시뮬레이션 결과
	let selected = 'COST';
	let anim = null;

	// ---- 게이지

	function even(sum) {
		const base = Math.floor(sum / FACTORY_ORDER.length);
		let rest = sum - base * FACTORY_ORDER.length;
		const v = {};
		FACTORY_ORDER.forEach(c => {
			v[c] = base + (rest > 0 ? 1 : 0);
			if (rest > 0) rest--;
		});
		return v;
	}

	function buildGauges() {
		gaugesEl.innerHTML = FACTORY_ORDER.map(c => `
			<div class="gauge" data-c="${c}">
				<input type="number" class="gnum" min="0" step="1">
				<input type="range" class="grange" min="0" step="1" orient="vertical">
				<div class="gname">${COUNTRY_LABEL[c]}</div>
				<div class="gvisits"></div>
			</div>`).join('');
		gaugesEl.querySelectorAll('.gauge').forEach(g => {
			const c = g.dataset.c;
			g.querySelector('.grange').addEventListener('input', e => move(c, parseInt(e.target.value, 10)));
			g.querySelector('.gnum').addEventListener('change', e => {
				const v = parseInt(e.target.value, 10);
				move(c, isNaN(v) ? values[c] : v);
			});
		});
		refreshGauges();
	}

	/** c 의 수량을 바꾸면 바로 오른쪽 나라가 반대로 조정된다(맨 오른쪽은 맨 왼쪽). 합계는 그대로. */
	function move(c, wanted) {
		const i = FACTORY_ORDER.indexOf(c);
		const neighbor = FACTORY_ORDER[(i + 1) % FACTORY_ORDER.length];
		const pair = values[c] + values[neighbor];
		const next = Math.max(0, Math.min(pair, wanted));
		values[c] = next;
		values[neighbor] = pair - next;
		refreshGauges();
		invalidate();
	}

	function refreshGauges() {
		gaugesEl.querySelectorAll('.gauge').forEach(g => {
			const c = g.dataset.c;
			g.querySelector('.gnum').value = values[c];
			const range = g.querySelector('.grange');
			range.max = total;
			range.value = values[c];
			const f = master && master.factories.find(x => x.country === c);
			g.querySelector('.gvisits').textContent = f && values[c] > 0
				? `방문 ${Math.ceil(values[c] / f.stockLimit)}회` : '방문 0회';
		});
	}

	totalInput.addEventListener('change', () => {
		const v = parseInt(totalInput.value, 10);
		if (!(v >= 1)) {
			totalInput.value = total;
			return;
		}
		total = v;
		values = even(total);
		refreshGauges();
		invalidate();
	});

	/** 입력이 바뀌면 이전 시뮬레이션 결과는 더 이상 이 입력의 결과가 아니다 */
	function invalidate() {
		result = null;
		resultEl.hidden = true;
		inputError.hidden = true;
	}

	// ---- 시뮬레이션

	function showError(el, msg) {
		el.textContent = msg;
		el.hidden = false;
	}

	runBtn.addEventListener('click', async () => {
		inputError.hidden = true;
		confirmError.hidden = true;
		runBtn.disabled = true;
		try {
			result = await postJson('/api/simulate', {orders: values});
			master = result.master;
			refreshGauges();
			selected = 'COST';
			renderResult(true);
			resultEl.hidden = false;
			resultEl.scrollIntoView({behavior: 'smooth', block: 'start'});
		} catch (e) {
			result = null;
			resultEl.hidden = true;
			showError(inputError, e.message);
		} finally {
			runBtn.disabled = false;
		}
	});

	function currentStored() {
		const plan = result.options.find(o => o.type === selected);
		return {master: result.master, orders: result.orders, visits: result.visits, plan};
	}

	// ---- 필요 납기일 (카드 표시용이며, 확정할 때 주문에 함께 저장한다. 계산에는 쓰지 않는다)

	const dueAsap = document.getElementById('dueAsap');
	const dueDays = document.getElementById('dueDays');
	const dueHint = document.getElementById('dueHint');
	const dueError = document.getElementById('dueError');

	/** 납기 입력 상태: 'asap'(가능한 빨리) | 'empty'(아직 안 적음) | 'invalid'(잘못된 값) | 'valid' */
	function dueState() {
		if (dueAsap.checked) return 'asap';
		const t = dueDays.value.trim();
		if (t === '') return 'empty';
		if (!/^\d+$/.test(t)) return 'invalid';
		const n = parseInt(t, 10);
		return n >= 5 && n <= 50 ? 'valid' : 'invalid';
	}

	/** 유효한 납기(일)를 돌려준다. 가능한 빨리이거나 비었거나 잘못된 입력이면 null */
	function currentDue() {
		return dueState() === 'valid' ? parseInt(dueDays.value.trim(), 10) : null;
	}

	function refreshDue() {
		const state = dueState();
		dueDays.disabled = state === 'asap';
		dueHint.hidden = state !== 'empty';      // 아직 안 적었을 때는 오류가 아니라 안내만 보인다
		dueError.hidden = state !== 'invalid';
		if (state === 'invalid') dueError.textContent = '필요 납기일은 5~50 사이의 정수로 입력하세요.';
		dueDays.setAttribute('aria-invalid', state === 'invalid' ? 'true' : 'false');
		if (result) renderOptions();   // 납기는 경로안 카드의 표시만 바꾼다. 요약·상세·지도는 다시 그리지 않는다
	}

	dueAsap.addEventListener('change', () => {
		refreshDue();
		if (!dueAsap.checked) dueDays.focus();   // 해제하면 바로 입력할 수 있게 한다
	});
	dueDays.addEventListener('input', refreshDue);

	/** 경로안 카드를 그린다(납기 초과 표시 포함). */
	function renderOptions() {
		const due = currentDue();
		optionsEl.innerHTML = result.options.map(o => `
			<button type="button" class="option ${o.type === selected ? 'selected' : ''} ${due !== null && o.totalDays > due ? 'late' : ''}" data-type="${o.type}">
				<strong>${o.label}</strong>
				<span>소요 ${o.totalDays}일</span>
				${due !== null && o.totalDays > due ? `<span class="late-note">납기 초과 ${o.totalDays - due}일</span>` : ''}
				<span>총 비용 ${eok(o.totalCost)}억원</span>
				<span class="muted">운항비 ${eok(o.shippingCost)}억원 · 배 ${o.ships.length}척</span>
			</button>`).join('');
		optionsEl.querySelectorAll('.option').forEach(b => b.addEventListener('click', () => {
			selected = b.dataset.type;
			renderResult(false);
		}));
	}

	function renderResult(first) {
		renderOptions();

		const stored = currentStored();
		PlanView.renderSummary(document.getElementById('summary'), stored);
		PlanView.renderDetails(document.getElementById('details'), stored);
		const animRoot = document.getElementById('anim');
		if (!anim) {
			anim = BatteryAnim.mount(animRoot, stored, {autoplay: true});
		} else {
			anim.update(stored, true);
		}
	}

	// ---- 주문 확정

	confirmBtn.addEventListener('click', async () => {
		if (!result) return;
		confirmError.hidden = true;
		const state = dueState();
		if (state === 'empty' || state === 'invalid') {
			showError(confirmError, '필요 납기일을 5~50 사이의 정수로 입력하거나 "가능한 빨리"를 체크하세요.');
			dueDays.focus();
			return;
		}
		confirmBtn.disabled = true;
		try {
			const res = await postJson('/api/orders', {
				orders: values,
				routeType: selected,
				masterVersion: result.master.version,
				memo: document.getElementById('memo').value,
				dueDays: currentDue()
			});
			location.href = '/orders/' + res.id;
		} catch (e) {
			showError(confirmError, e.message);
			confirmBtn.disabled = false;
		}
	});

	// ---- 시작

	values = even(total);
	buildGauges();
	fetch('/api/master').then(r => r.json()).then(m => {
		master = m;
		refreshGauges();
	}).catch(() => { /* 방문 횟수 표시만 비어 있게 둔다 */ });
})();
