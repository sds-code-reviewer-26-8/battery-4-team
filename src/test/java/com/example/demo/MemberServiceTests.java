package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.entity.Member;
import com.example.demo.repository.MemberRepository;
import com.example.demo.service.BusinessException;
import com.example.demo.service.MemberService;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 가입 규칙 경계값과 회원별 해시 파라미터 저장·하위 호환 */
@SpringBootTest
class MemberServiceTests {

	private static final String SALT = Base64.getEncoder().encodeToString(new byte[16]);

	@Autowired
	MemberService service;

	@Autowired
	MemberRepository repository;

	/** 가입이 규칙 위반 안내로 거부되는지. 다른 이유(DB 제약 등)로 거부된 것은 통과로 치지 않는다. */
	private void assertRejected(String username, String password, String reasonStartsWith) {
		assertThatThrownBy(() -> service.signup(username, password))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining(reasonStartsWith);
	}

	@Test
	void usernameLengthBoundary() {
		assertRejected("bu", "abcd", "아이디는");
		assertThatCode(() -> service.signup("bnd3", "abcd")).doesNotThrowAnyException();
		assertThatCode(() -> service.signup("b".repeat(19) + "1", "abcd")).doesNotThrowAnyException();
		assertRejected("b".repeat(21), "abcd", "아이디는");
	}

	@Test
	void passwordLengthBoundary() {
		assertRejected("pwlen3", "abc", "비밀번호는");
		assertThatCode(() -> service.signup("pwlen4", "abcd")).doesNotThrowAnyException();
		assertThatCode(() -> service.signup("pwlen100", "p".repeat(100))).doesNotThrowAnyException();
		assertRejected("pwlen101", "p".repeat(101), "비밀번호는");
		assertThat(service.authenticate("pwlen100", "p".repeat(100))).isTrue();
	}

	@Test
	void signupStoresHashParams() {
		service.signup("params1", "secret1");
		Member m = repository.findByUsername("params1").orElseThrow();
		assertThat(m.getHashAlgorithm()).isEqualTo("PBKDF2WithHmacSHA256");
		assertThat(m.getIterations()).isEqualTo(65536);
		assertThat(service.authenticate("params1", "secret1")).isTrue();
		assertThat(service.authenticate("params1", "wrong")).isFalse();
	}

	@Test
	void memberWithDifferentIterationsCanLogin() {
		repository.saveAndFlush(new Member("iter1000", hash("pw1234", 1000), SALT, "PBKDF2WithHmacSHA256", 1000));
		assertThat(service.authenticate("iter1000", "pw1234")).isTrue();
		assertThat(service.authenticate("iter1000", "bad")).isFalse();
	}

	@Test
	void legacyMemberWithoutParamsCanLogin() {
		repository.saveAndFlush(new Member("legacy1", hash("pw1234", 65536), SALT));
		Member m = repository.findByUsername("legacy1").orElseThrow();
		assertThat(m.getHashAlgorithm()).isNull();
		assertThat(m.getIterations()).isNull();
		assertThat(service.authenticate("legacy1", "pw1234")).isTrue();
		assertThat(service.authenticate("legacy1", "wrong")).isFalse();
	}

	private static String hash(String password, int iterations) {
		try {
			PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(SALT), iterations, 256);
			return Base64.getEncoder().encodeToString(
					SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
