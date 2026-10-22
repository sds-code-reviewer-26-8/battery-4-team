(function () {
	const orderId = location.pathname.split('/').pop();
	const titleEl = document.getElementById('orderTitle');
	const messagesEl = document.getElementById('messages');
	const infoEl = document.getElementById('orderInfo');
	const historyEl = document.getElementById('historyRows');
	let version = null;        // 화면에서 본 시점의 주문 version. 상태 변경 때 함께 보낸다.
	let planDrawn = false;     // 저장된 계획은 바뀌지 않으므로 한 번만 그린다.

	function showMessages(message, error) {
		messagesEl.innerHTML = (message ? `<p class="alert ok">${esc(message)}</p>` : '')
			+ (error ? `<p class="alert error">${esc(error)}</p>` : '');
	}

	/** 필요 납기. 소요 일수가 납기를 넘으면 빨간색과 글자로 함께 알린다. */
	function dueText(order) {
		if (order.dueDays == null) return '가능한 빨리';
		return order.dueOverDays != null
			? `<span class="late-text">${order.dueDays}일 · 납기 초과 ${order.dueOverDays}일</span>`
			: `${order.dueDays}일`;
	}

	function render(order) {
		version = order.version;
		titleEl.innerHTML = `주문 <span>#${order.id}</span>
			<span class="badge badge-${esc(order.status)}">${esc(order.statusLabel)}</span>`;

		infoEl.innerHTML = `
			<dl class="meta">
				<div><dt>확정 일시</dt><dd>${esc(order.confirmedAt)}</dd></div>
				<div><dt>작성자</dt><dd>${esc(order.createdBy)}</dd></div>
				<div><dt>경로</dt><dd>${esc(order.routeLabel)}</dd></div>
				<div><dt>필요 납기</dt><dd>${dueText(order)}</dd></div>
				<div><dt>메모</dt><dd>${esc(order.memo != null ? order.memo : '-')}</dd></div>
				<div><dt>고객사</dt><dd>${esc(order.customerName != null ? order.customerName : '-')}</dd></div>
			</dl>`
			+ (order.customerEditable
				? `<form class="customer-form">
					<label class="stack">고객사 이름 수정
						<input type="text" id="customerInput" maxlength="100" value="${esc(order.customerName != null ? order.customerName : '')}"></label>
					<button type="submit" class="primary">고객사 저장</button>
				</form>`
				: '')
			+ (order.next.length === 0
				? '<p class="muted">최종 상태라 더 바꿀 수 없습니다.</p>'
				: `<div class="actions">${order.next.map(s => `
					<form class="status-form" data-status="${esc(s.status)}" data-confirm="${esc(s.label)}(으)로 바꿉니다. 계속할까요?">
						<button type="submit" class="${s.status === 'CANCELED' ? 'danger' : 'primary'}">${esc(s.label)}(으)로 변경</button>
					</form>`).join('')}</div>`);

		historyEl.innerHTML = order.history.map(h => `
			<tr>
				<td>${esc(h.changedAt)}</td>
				<td>${esc(h.from != null ? h.from : '-')}</td>
				<td>${esc(h.to)}</td>
				<td>${esc(h.changedBy)}</td>
			</tr>`).join('');

		if (!planDrawn) {
			planDrawn = true;
			const stored = JSON.parse(order.planJson);
			PlanView.renderSummary(document.getElementById('summary'), stored);
			PlanView.renderDetails(document.getElementById('details'), stored);
			BatteryAnim.mount(document.getElementById('anim'), stored, {autoplay: false});
		}
	}

	/** 취소 후 돌아갈 목록 주소. 필터를 걸어 보던 목록(/orders?...)에서 들어왔으면 그 주소, 아니면 전체 목록. */
	function previousList() {
		try {
			const ref = new URL(document.referrer);
			if (ref.origin === location.origin && ref.pathname === '/orders') return ref.pathname + ref.search;
		} catch (ignore) { /* 직접 연 상세 등 referrer 가 없으면 전체 목록 */ }
		return '/orders';
	}

	/** 주문을 새로 읽어 화면을 갱신한다. 안내 문구는 갱신된 화면과 함께 보여 준다. */
	async function load(message, error) {
		try {
			render(await getJson('/api/orders/' + orderId));
			showMessages(message, error);
		} catch (err) {
			showMessages(null, error || err.message);
		}
	}

	infoEl.addEventListener('submit', async e => {
		e.preventDefault();
		if (e.target.closest('.customer-form')) {
			try {
				const res = await postJson('/api/orders/' + orderId + '/customer',
					{customerName: document.getElementById('customerInput').value, version: version});
				await load(res.message, null);
			} catch (err) {
				// 충돌(409)은 다시 읽어 현재 값을 보여 주고, 입력 오류(400)는 입력값을 둔 채 이유만 보인다.
				if (err.conflict) await load(null, err.message);
				else showMessages(null, err.message);
			}
			return;
		}
		const f = e.target.closest('.status-form');
		if (!f || !confirm(f.dataset.confirm)) return;
		try {
			const res = await postJson('/api/orders/' + orderId + '/status',
				{status: f.dataset.status, version: version});
			if (f.dataset.status === 'CANCELED') {
				// 취소하면 주문이 삭제되므로 상세가 아니라 이전 페이지(주문 목록)로 이동한다.
				// 성공 안내는 목록에서 한 번 보여 주려고 넘긴다. 저장소를 못 써도 이동은 한다.
				try { sessionStorage.setItem('flashMessage', res.message); } catch (ignore) { /* 안내만 생략 */ }
				// replace 로 현재(삭제된) 상세 기록을 목록으로 바꿔, 뒤로 가기로 오류 화면이 다시 열리지 않게 한다.
				location.replace(previousList());
				return;
			}
			await load(res.message, null);
		} catch (err) {
			await load(null, err.message);
		}
	});

	load(null, null);
})();
