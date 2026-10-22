(function () {
	const form = document.getElementById('signupForm');
	let errorEl = null;

	form.addEventListener('submit', async e => {
		e.preventDefault();
		if (errorEl) errorEl.remove();
		errorEl = null;
		try {
			await postJson('/api/signup', {
				username: form.elements.username.value,
				password: form.elements.password.value
			});
			location.href = '/login';
		} catch (err) {
			errorEl = document.createElement('p');
			errorEl.className = 'alert error';
			errorEl.textContent = err.message;
			form.before(errorEl);
		}
	});
})();
