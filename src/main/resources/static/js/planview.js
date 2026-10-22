/** 계획(stored = {master, orders, visits, plan})을 표로 보여 준다. 주문 추가와 주문 상세가 함께 쓴다. */
const PlanView = (function () {

	/** 운영비 도입 전에 확정한 주문의 계획에는 운영비 값이 없다. 0원이 아니라 '없음'으로 보여 준다. */
	const hasOperating = p => p.operatingCost !== undefined && p.operatingCost !== null;
	const operatingText = p => hasOperating(p) ? `${eok(p.operatingCost)}억원` : '없음';

	/** 운영비를 반영하기 전에 확정한 주문이면 안 이름과 비용이 당시 기준임을 알린다. */
	const oldPlanNote = p => {
		if (hasOperating(p)) return '';
		const costNote = p.type === 'COST'
			? ` '${p.label}' 안은 운항비만 비교해 고른 안이라 지금의 '총비용 최소'와 기준이 다릅니다.`
			: ' 총 비용에 운영비가 들어 있지 않습니다.';
		return `<p class="muted old-plan-note">운영비를 반영하기 전에 확정한 주문입니다.${costNote}</p>`;
	};

	function renderSummary(root, stored) {
		const p = stored.plan;
		const totalK = Object.values(stored.orders).reduce((a, b) => a + b, 0);
		root.innerHTML = `
			<section class="card">
				<h2>요약 <span class="muted">· ${p.label}</span></h2>
				${oldPlanNote(p)}
				<div class="stats">
					<div><span>총 수량</span><b>${fmt(totalK)}K</b></div>
					<div><span>소요 일수</span><b>${p.totalDays}일</b></div>
					<div><span>생산 원가</span><b>${eok(p.productionCost)}억원</b></div>
					<div><span>운항비</span><b>${eok(p.shippingCost)}억원</b></div>
					<div><span>운영비</span><b>${operatingText(p)}</b></div>
					<div class="strong"><span>총 비용</span><b>${eok(p.totalCost)}억원</b></div>
				</div>
			</section>`;
	}

	function renderDetails(root, stored) {
		root.innerHTML = ordersTable(stored) + shipsTable(stored) + dailyTable(stored) + masterTable(stored)
			+ operationTable(stored);
	}

	function ordersTable(stored) {
		const rows = FACTORY_ORDER.map(c => `
			<tr><td>${COUNTRY_LABEL[c]}</td><td class="num">${fmt(stored.orders[c] || 0)}</td>
			<td class="num">${stored.visits[c] || 0}회</td></tr>`).join('');
		return `<h2>나라별 주문 수량</h2>
			<table class="grid"><thead><tr><th>나라</th><th class="num">주문 수량(K)</th><th class="num">필요 방문 횟수</th></tr></thead>
			<tbody>${rows}</tbody></table>`;
	}

	function shipsTable(stored) {
		const rows = [];
		for (const ship of stored.plan.ships) {
			ship.trips.forEach((trip, k) => {
				const path = ['한국'].concat(trip.stops.map(s =>
					`${COUNTRY_LABEL[s.country]}<small>(${s.arriveDay}일 도착 · ${fmt(s.loadK)}K 적재)</small>`)).concat(['한국']);
				rows.push(`<tr><td class="nw">${ship.no}호</td><td class="nw">${k + 1}</td><td class="num nw">${trip.departDay}일</td>
					<td>${path.join(' → ')}</td><td class="num nw">${trip.returnDay}일</td>
					<td class="num nw">${trip.returnDay - trip.departDay}일</td></tr>`);
			});
		}
		return `<h2>배 경로 (왕복별 일정)</h2>
			<table class="grid"><thead><tr><th>배</th><th>왕복</th><th class="num">출항</th><th>경로</th>
			<th class="num">귀환</th><th class="num">운항 일수</th></tr></thead><tbody>${rows.join('')}</tbody></table>`;
	}

	function dailyTable(stored) {
		const p = stored.plan;
		const unit = {};
		stored.master.factories.forEach(f => unit[f.country] = f.unitCost);
		const head = FACTORY_ORDER.map(c => `<th class="num">${COUNTRY_LABEL[c]} 생산(K)</th>`).join('');
		const rows = [];
		let cum = 0;
		for (let d = 0; d < p.totalDays; d++) {
			let won = 0;
			const cells = FACTORY_ORDER.map(c => {
				const made = (p.production[c] || [])[d] || 0;
				won += made * 1000 * unit[c];
				return `<td class="num">${made ? fmt(made) : '-'}</td>`;
			}).join('');
			cum += won;
			rows.push(`<tr><td>${d + 1}일차</td>${cells}<td class="num">${eok(won)}</td><td class="num">${eok(cum)}</td></tr>`);
		}
		return `<h2>일별 생산·비용</h2>
			<details><summary>${p.totalDays}일 보기</summary>
			<table class="grid"><thead><tr><th>일</th>${head}<th class="num">생산 원가(억원)</th><th class="num">누적(억원)</th></tr></thead>
			<tbody>${rows.join('')}</tbody></table></details>`;
	}

	function masterTable(stored) {
		const m = stored.master;
		const factories = m.factories.map(f => `<tr><td>${COUNTRY_LABEL[f.country]}</td>
			<td class="num">${fmt(f.maxDaily)}</td><td class="num">${fmt(f.stockLimit)}</td><td class="num">${fmt(f.unitCost)}</td>
			<td class="num">${f.dailyOperatingCostWon == null ? '없음' : eok(f.dailyOperatingCostWon)}</td></tr>`).join('');
		const routes = m.routes.map(r => `<tr><td>${COUNTRY_LABEL[r.from]} - ${COUNTRY_LABEL[r.to]}</td>
			<td class="num">${r.days}일</td></tr>`).join('');
		return `<h2>계획에 쓴 마스터 값</h2>
			<details><summary>보기</summary>
			<p>배 ${m.shipCount}척 · 척당 일 운항비 ${eok(m.shipDailyCostWon)}억원</p>
			<table class="grid"><thead><tr><th>나라</th><th class="num">하루 최대(K)</th><th class="num">재고 상한(K)</th><th class="num">셀당 원가(원)</th><th class="num">하루 운영비(억원)</th></tr></thead>
			<tbody>${factories}</tbody></table>
			<table class="grid"><thead><tr><th>항로</th><th class="num">항해 일수</th></tr></thead><tbody>${routes}</tbody></table>
			</details>`;
	}

	/** 공장별 운영 일수(주문일부터 마지막 수거일)와 운영비. 다른 표의 순서가 바뀌지 않도록 맨 뒤에 둔다. */
	function operationTable(stored) {
		const p = stored.plan;
		if (!hasOperating(p) || !p.factoryOperations) {
			return `<h2>공장별 운영 일수·운영비</h2>
				<p class="muted">이 주문은 공장 운영비를 반영하기 전에 확정되어 운영비 정보가 없습니다.</p>`;
		}
		const rows = p.factoryOperations.map(o => `<tr><td>${COUNTRY_LABEL[o.country]}</td>
			<td class="num">${o.days}일</td><td class="num">${eok(o.cost)}</td></tr>`).join('');
		return `<h2>공장별 운영 일수·운영비</h2>
			<table class="grid"><thead><tr><th>나라</th><th class="num">운영 일수</th><th class="num">운영비(억원)</th></tr></thead>
			<tbody>${rows}</tbody></table>
			<p class="muted">운영 일수는 주문일(0일차)부터 그 공장의 마지막 수거일까지입니다. 주문이 없는 공장은 0입니다.</p>`;
	}

	return {renderSummary, renderDetails};
})();
