(function () {
	const filtersEl = document.getElementById('filters');
	const rowsEl = document.getElementById('orderRows');
	const loadError = document.getElementById('loadError');
	const messagesEl = document.getElementById('messages');
	const status = new URLSearchParams(location.search).get('status');

	/** 필요 납기 칸. 납기를 넘으면 빨간색과 글자로 함께 알린다. */
	function dueCell(o) {
		if (o.dueDays == null) return '가능한 빨리';
		return o.dueOverDays != null
			? `<span class="late-text">${o.dueDays}일 · 납기 초과 ${o.dueOverDays}일</span>`
			: `${o.dueDays}일`;
	}

	function render(data) {
		const chip = (href, active, label, count) =>
			`<a href="${href}" class="chip${active ? ' active' : ''}">${label} <span class="count">${count}</span></a>`;
		filtersEl.innerHTML = chip('/orders', !status, '전체', data.total)
			+ data.statuses.map(s => chip('/orders?status=' + s.status, status === s.status,
				`<span>${esc(s.label)}</span>`, s.count)).join('');

		rowsEl.innerHTML = data.orders.length === 0
			? '<tr><td colspan="9" class="empty">주문이 없습니다.</td></tr>'
			: data.orders.map(o => `
				<tr class="clickable" data-id="${o.id}">
					<td><a href="/orders/${o.id}">#${o.id}</a></td>
					<td>${esc(o.confirmedAt)}</td>
					<td class="num">${fmt(o.totalK)}</td>
					<td>${esc(o.routeLabel)}</td>
					<td class="num">${o.totalDays}</td>
					<td class="num">${esc(o.totalCostEok)}</td>
					<td><span class="badge badge-${esc(o.status)}">${esc(o.statusLabel)}</span></td>
					<td>${esc(o.createdBy)}</td>
					<td>${dueCell(o)}</td>
				</tr>`).join('');
	}

	rowsEl.addEventListener('click', e => {
		const row = e.target.closest('tr.clickable');
		if (row) location.href = '/orders/' + row.dataset.id;
	});

	// 취소 직후 넘어온 안내를 한 번만 보여 준다. 읽자마자 지우므로 새로고침하면 사라진다.
	try {
		const flash = sessionStorage.getItem('flashMessage');
		if (flash) {
			sessionStorage.removeItem('flashMessage');
			messagesEl.innerHTML = `<p class="alert ok">${esc(flash)}</p>`;
		}
	} catch (ignore) { /* 안내만 생략 */ }

	getJson('/api/orders' + (status ? '?status=' + encodeURIComponent(status) : ''))
		.then(render)
		.catch(err => {
			loadError.innerHTML = `<p class="alert error">${esc(err.message)}</p>`;
		});
})();
