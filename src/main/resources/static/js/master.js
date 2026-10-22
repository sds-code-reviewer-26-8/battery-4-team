(function () {
	const formEl = document.getElementById('masterForm');
	const messagesEl = document.getElementById('messages');

	const input = (name, form, attrs) =>
		`<input ${attrs} name="${esc(name)}" value="${esc(form[name] ?? '')}">`;

	function render(data) {
		const form = data.form;
		const countries = data.countries;
		formEl.innerHTML = `
			<section class="card">
				<h2>공장</h2>
				<table class="grid">
					<thead><tr><th>나라</th><th>하루 최대 생산량(K)</th><th>재고 상한(K)</th><th>셀당 원가(원)</th><th>하루 운영비(억 원)</th></tr></thead>
					<tbody>${data.factories.map(c => `
						<tr>
							<td>${esc(c.label)}</td>
							<td>${input('maxDaily_' + c.name, form, 'type="number" min="1"')}</td>
							<td>${input('stockLimit_' + c.name, form, 'type="number" min="1"')}</td>
							<td>${input('unitCost_' + c.name, form, 'type="number" min="1"')}</td>
							<td>${input('operatingCost_' + c.name, form, 'type="text" inputmode="decimal"')}</td>
						</tr>`).join('')}
					</tbody>
				</table>
			</section>

			<section class="card">
				<h2>선박</h2>
				<div class="row">
					<label>배 척수 (1~4)
						${input('shipCount', form, 'type="number" min="1" max="4"')}</label>
					<label>척당 일 운항비(억 원)
						${input('shipDailyCost', form, 'type="text" inputmode="decimal"')}</label>
				</div>
			</section>

			<section class="card">
				<h2>항로 (항해 일수, 양방향 동일)</h2>
				<table class="grid matrix">
					<thead>
					<tr><th></th>${countries.slice(1).map(c => `<th>${esc(c.label)}</th>`).join('')}</tr>
					</thead>
					<tbody>${countries.slice(0, -1).map((a, ai) => `
						<tr>
							<th>${esc(a.label)}</th>
							${countries.slice(1).map((b, k) => '<td>' + (k + 1 > ai
								? input('days_' + a.name + '_' + b.name, form, 'type="number" min="1"')
								: '<span class="muted">-</span>') + '</td>').join('')}
						</tr>`).join('')}
					</tbody>
				</table>
			</section>

			<button type="submit" class="primary">저장</button>`;
	}

	function showMessage(message) {
		messagesEl.innerHTML = `<p class="alert ok">${esc(message)}</p>`;
	}

	function showErrors(errors) {
		messagesEl.innerHTML = `<div class="alert error">
			<strong>저장하지 않았습니다. 아래 값을 확인하세요.</strong>
			<ul>${errors.map(e => `<li>${esc(e)}</li>`).join('')}</ul></div>`;
	}

	async function load() {
		render(await getJson('/api/master/form'));
	}

	formEl.addEventListener('submit', async e => {
		e.preventDefault();
		const values = {};
		formEl.querySelectorAll('input[name]').forEach(i => {
			values[i.name] = i.value;
		});
		try {
			const res = await postJson('/api/master', values);
			await load();   // 저장된 값으로 다시 그린다
			showMessage(res.message);
		} catch (err) {
			// 입력한 값은 그대로 두고 이유만 보여 준다
			showErrors(err.messages || [err.message]);
		}
	});

	load().catch(err => showErrors([err.message]));
})();
